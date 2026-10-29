// Host harness for Win-FG's per-size scratch (descriptor sets + UBOs), driven by test_winfg_scratch.py.
// A fake device stands in for Arm's Mali driver: its descriptor pool is held to the sizes it was made
// with, a failed allocation returns VK_ERROR_OUT_OF_POOL_MEMORY, and a write to a null or dead set
// aborts (Arm's driver faults there: ML-PY4J's crash in write_descriptor_set).
#include "framegen.cpp"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <map>
#include <set>
#include <string>

namespace {

uint64_t g_next = 0x1000;
template <typename T> T handle() { return reinterpret_cast<T>(static_cast<uintptr_t>(g_next += 0x10)); }

struct Pool {
    uint32_t maxSets = 0, sets = 0;
    std::map<VkDescriptorType, uint32_t> cap, used;
    std::set<VkDescriptorSet> live;
};
std::map<VkDescriptorPool, Pool> g_pools;
std::map<VkDescriptorSetLayout, std::map<VkDescriptorType, uint32_t>> g_layouts;
std::map<VkDescriptorSet, std::pair<VkDescriptorPool, VkDescriptorSetLayout>> g_setOwner;
std::set<VkBuffer> g_buffers;
std::map<VkDeviceMemory, void*> g_mem;
int g_poolScale = 1;   // divides the pool's sizes (the "exhausted" case)
int g_writes = 0;

[[noreturn]] void die(const char* why) { std::printf("FAIL %s\n", why); std::fflush(stdout); std::abort(); }

VKAPI_ATTR void VKAPI_CALL memProps(VkPhysicalDevice, VkPhysicalDeviceMemoryProperties* p) {
    std::memset(p, 0, sizeof(*p));
    p->memoryTypeCount = 1;
    p->memoryTypes[0].propertyFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT |
                                      VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
    p->memoryHeapCount = 1;
}

VKAPI_ATTR VkResult VKAPI_CALL createPool(VkDevice, const VkDescriptorPoolCreateInfo* ci, const VkAllocationCallbacks*,
                                          VkDescriptorPool* out) {
    Pool p; p.maxSets = ci->maxSets / g_poolScale;
    for (uint32_t i = 0; i < ci->poolSizeCount; ++i) p.cap[ci->pPoolSizes[i].type] += ci->pPoolSizes[i].descriptorCount / g_poolScale;
    *out = handle<VkDescriptorPool>(); g_pools[*out] = p; return VK_SUCCESS;
}
void killSets(Pool& p) { for (VkDescriptorSet s : p.live) g_setOwner.erase(s); p.live.clear(); p.used.clear(); p.sets = 0; }
VKAPI_ATTR void VKAPI_CALL destroyPool(VkDevice, VkDescriptorPool pool, const VkAllocationCallbacks*) {
    auto it = g_pools.find(pool); if (it == g_pools.end()) die("destroying an unknown pool");
    killSets(it->second); g_pools.erase(it);
}
VKAPI_ATTR VkResult VKAPI_CALL resetPool(VkDevice, VkDescriptorPool pool, VkDescriptorPoolResetFlags) {
    auto it = g_pools.find(pool); if (it == g_pools.end()) die("resetting an unknown pool");
    killSets(it->second); return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL createSetLayout(VkDevice, const VkDescriptorSetLayoutCreateInfo* ci, const VkAllocationCallbacks*,
                                               VkDescriptorSetLayout* out) {
    *out = handle<VkDescriptorSetLayout>();
    auto& m = g_layouts[*out];
    for (uint32_t i = 0; i < ci->bindingCount; ++i) m[ci->pBindings[i].descriptorType] += ci->pBindings[i].descriptorCount;
    return VK_SUCCESS;
}
VKAPI_ATTR VkResult VKAPI_CALL allocSets(VkDevice, const VkDescriptorSetAllocateInfo* ai, VkDescriptorSet* out) {
    auto it = g_pools.find(ai->descriptorPool); if (it == g_pools.end()) die("allocating from an unknown pool");
    Pool& p = it->second;
    for (uint32_t n = 0; n < ai->descriptorSetCount; ++n) {
        const auto& need = g_layouts.at(ai->pSetLayouts[n]);
        if (p.sets + 1 > p.maxSets) return VK_ERROR_OUT_OF_POOL_MEMORY;
        for (auto& kv : need) if (p.used[kv.first] + kv.second > p.cap[kv.first]) return VK_ERROR_OUT_OF_POOL_MEMORY;
        for (auto& kv : need) p.used[kv.first] += kv.second;
        p.sets++;
        out[n] = handle<VkDescriptorSet>();
        p.live.insert(out[n]);
        g_setOwner[out[n]] = {ai->descriptorPool, ai->pSetLayouts[n]};
    }
    return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL updateSets(VkDevice, uint32_t n, const VkWriteDescriptorSet* w, uint32_t, const VkCopyDescriptorSet*) {
    for (uint32_t i = 0; i < n; ++i) {
        if (!w[i].dstSet) die("write to a null descriptor set");
        if (!g_setOwner.count(w[i].dstSet)) die("write to a descriptor set of a reset or destroyed pool");
        g_writes++;
    }
}
VKAPI_ATTR void VKAPI_CALL bindSets(VkCommandBuffer, VkPipelineBindPoint, VkPipelineLayout, uint32_t, uint32_t n,
                                    const VkDescriptorSet* s, uint32_t, const uint32_t*) {
    for (uint32_t i = 0; i < n; ++i) if (!s[i] || !g_setOwner.count(s[i])) die("binding a null or dead descriptor set");
}

VKAPI_ATTR VkResult VKAPI_CALL createBuffer(VkDevice, const VkBufferCreateInfo*, const VkAllocationCallbacks*, VkBuffer* b) {
    *b = handle<VkBuffer>(); g_buffers.insert(*b); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroyBuffer(VkDevice, VkBuffer b, const VkAllocationCallbacks*) { g_buffers.erase(b); }
VKAPI_ATTR void VKAPI_CALL bufReqs(VkDevice, VkBuffer, VkMemoryRequirements* r) { r->size = 65536; r->alignment = 256; r->memoryTypeBits = 1; }
VKAPI_ATTR void VKAPI_CALL imgReqs(VkDevice, VkImage, VkMemoryRequirements* r) { r->size = 4096; r->alignment = 256; r->memoryTypeBits = 1; }
VKAPI_ATTR VkResult VKAPI_CALL allocMem(VkDevice, const VkMemoryAllocateInfo* ai, const VkAllocationCallbacks*, VkDeviceMemory* m) {
    *m = handle<VkDeviceMemory>(); g_mem[*m] = std::calloc(1, (size_t)ai->allocationSize); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL freeMem(VkDevice, VkDeviceMemory m, const VkAllocationCallbacks*) {
    auto it = g_mem.find(m); if (it != g_mem.end()) { std::free(it->second); g_mem.erase(it); }
}
VKAPI_ATTR VkResult VKAPI_CALL mapMem(VkDevice, VkDeviceMemory m, VkDeviceSize, VkDeviceSize, VkMemoryMapFlags, void** p) {
    *p = g_mem.at(m); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL unmapMem(VkDevice, VkDeviceMemory) {}
VKAPI_ATTR VkResult VKAPI_CALL ok3(VkDevice, VkBuffer, VkDeviceMemory, VkDeviceSize) { return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL okImgBind(VkDevice, VkImage, VkDeviceMemory, VkDeviceSize) { return VK_SUCCESS; }
VKAPI_ATTR VkResult VKAPI_CALL createImage(VkDevice, const VkImageCreateInfo*, const VkAllocationCallbacks*, VkImage* i) { *i = handle<VkImage>(); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL destroyImage(VkDevice, VkImage, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL createView(VkDevice, const VkImageViewCreateInfo*, const VkAllocationCallbacks*, VkImageView* v) { *v = handle<VkImageView>(); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL destroyView(VkDevice, VkImageView, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL createSampler(VkDevice, const VkSamplerCreateInfo*, const VkAllocationCallbacks*, VkSampler* s) { *s = handle<VkSampler>(); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL destroySampler(VkDevice, VkSampler, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL createModule(VkDevice, const VkShaderModuleCreateInfo*, const VkAllocationCallbacks*, VkShaderModule* m) { *m = handle<VkShaderModule>(); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL destroyModule(VkDevice, VkShaderModule, const VkAllocationCallbacks*) {}
VKAPI_ATTR void VKAPI_CALL destroySetLayout(VkDevice, VkDescriptorSetLayout, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL createPipeLayout(VkDevice, const VkPipelineLayoutCreateInfo*, const VkAllocationCallbacks*, VkPipelineLayout* l) { *l = handle<VkPipelineLayout>(); return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL destroyPipeLayout(VkDevice, VkPipelineLayout, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL createPipes(VkDevice, VkPipelineCache, uint32_t n, const VkComputePipelineCreateInfo*, const VkAllocationCallbacks*, VkPipeline* p) {
    for (uint32_t i = 0; i < n; ++i) p[i] = handle<VkPipeline>(); return VK_SUCCESS;
}
VKAPI_ATTR void VKAPI_CALL destroyPipe(VkDevice, VkPipeline, const VkAllocationCallbacks*) {}
VKAPI_ATTR VkResult VKAPI_CALL waitIdle(VkDevice) { return VK_SUCCESS; }
VKAPI_ATTR void VKAPI_CALL cmdBindPipe(VkCommandBuffer, VkPipelineBindPoint, VkPipeline) {}
VKAPI_ATTR void VKAPI_CALL cmdDispatch(VkCommandBuffer, uint32_t, uint32_t, uint32_t) {}
VKAPI_ATTR void VKAPI_CALL cmdBarrier(VkCommandBuffer, VkPipelineStageFlags, VkPipelineStageFlags, VkDependencyFlags, uint32_t,
                                      const VkMemoryBarrier*, uint32_t, const VkBufferMemoryBarrier*, uint32_t, const VkImageMemoryBarrier*) {}
VKAPI_ATTR void VKAPI_CALL cmdCopyImage(VkCommandBuffer, VkImage, VkImageLayout, VkImage, VkImageLayout, uint32_t, const VkImageCopy*) {}
VKAPI_ATTR void VKAPI_CALL cmdClear(VkCommandBuffer, VkImage, VkImageLayout, const VkClearColorValue*, uint32_t, const VkImageSubresourceRange*) {}

winfg::DeviceDispatch makeDd() {
    winfg::DeviceDispatch d{};
    d.CreateDescriptorPool = createPool; d.DestroyDescriptorPool = destroyPool; d.ResetDescriptorPool = resetPool;
    d.CreateDescriptorSetLayout = createSetLayout; d.DestroyDescriptorSetLayout = destroySetLayout;
    d.AllocateDescriptorSets = allocSets; d.UpdateDescriptorSets = updateSets; d.CmdBindDescriptorSets = bindSets;
    d.CreateBuffer = createBuffer; d.DestroyBuffer = destroyBuffer; d.GetBufferMemoryRequirements = bufReqs;
    d.GetImageMemoryRequirements = imgReqs; d.AllocateMemory = allocMem; d.FreeMemory = freeMem;
    d.MapMemory = mapMem; d.UnmapMemory = unmapMem; d.BindBufferMemory = ok3; d.BindImageMemory = okImgBind;
    d.CreateImage = createImage; d.DestroyImage = destroyImage; d.CreateImageView = createView; d.DestroyImageView = destroyView;
    d.CreateSampler = createSampler; d.DestroySampler = destroySampler;
    d.CreateShaderModule = createModule; d.DestroyShaderModule = destroyModule;
    d.CreatePipelineLayout = createPipeLayout; d.DestroyPipelineLayout = destroyPipeLayout;
    d.CreateComputePipelines = createPipes; d.DestroyPipeline = destroyPipe; d.DeviceWaitIdle = waitIdle;
    d.CmdBindPipeline = cmdBindPipe; d.CmdDispatch = cmdDispatch; d.CmdPipelineBarrier = cmdBarrier;
    d.CmdCopyImage = cmdCopyImage; d.CmdClearColorImage = cmdClear;
    return d;
}

struct Rig {
    winfg::DeviceDispatch dd = makeDd();
    winfg::InstanceDispatch id{};
    winfg::FrameGen fg;
    VkDevice dev = handle<VkDevice>();
    Rig() { id.GetPhysicalDeviceMemoryProperties = memProps; }
    bool init() { return fg.init(&dd, &id, handle<VkPhysicalDevice>(), dev, 0, handle<VkQueue>()); }
    void frame(uint32_t w, uint32_t h) {
        if (!fg.onResize(VkExtent2D{w, h}, VK_FORMAT_R8G8B8A8_UNORM)) die("onResize failed");
        VkImageView a = handle<VkImageView>(), b = handle<VkImageView>(), o = handle<VkImageView>();
        fg.record(handle<VkCommandBuffer>(), a, b, o, 0.5f, 0);
    }
};

size_t liveSets() { size_t n = 0; for (auto& kv : g_pools) n += kv.second.live.size(); return n; }

}  // namespace

int main(int argc, char** argv) {
    std::string c = argc > 1 ? argv[1] : "";
    // The sizes the compositor's scene went through in ML-PY4J's session: no window, Big Picture, back.
    const uint32_t sizes[][2] = {{1920, 1080}, {1280, 800}, {1920, 1080}, {1280, 800}, {24, 24},
                                 {1280, 800}, {1920, 1080}, {1600, 720}, {1280, 800}, {1920, 1080}};
    if (c == "resize") {
        Rig r; if (!r.init()) die("init");
        size_t first = 0, firstBuf = 0;
        for (int round = 0; round < 3; ++round)
            for (auto& s : sizes) {
                for (int f = 0; f < 3; ++f) r.frame(s[0], s[1]);
                if (r.fg.scratchFailed()) die("scratch failed on a resize");
                if (!first) { first = liveSets(); firstBuf = g_buffers.size(); }
                if (liveSets() != first) die("descriptor sets pile up across resizes");
                if (g_buffers.size() != firstBuf) die("uniform buffers leak across resizes");
            }
        r.fg.destroy();
        if (!g_buffers.empty()) die("buffers left after destroy");
    } else if (c == "reinit") {
        {
            Rig r; if (!r.init()) die("init");
            r.frame(1280, 800);
            r.fg.destroy();
        }
        Rig r2; if (!r2.init()) die("init 2");
        r2.frame(1280, 800);   // same size: must not reuse the first FrameGen's sets
        r2.fg.destroy();
        if (!g_buffers.empty()) die("buffers left after destroy");
    } else if (c == "exhausted") {
        g_poolScale = 8;       // a pool too small for even one build
        Rig r; if (!r.init()) die("init");
        r.frame(1280, 800);
        if (!r.fg.scratchFailed()) die("the failed build was not reported");
        r.frame(1280, 800);    // tried again (the engine stops before this): still no crash
        r.fg.destroy();
    } else {
        die("unknown case");
    }
    std::printf("PASS\n");
    return 0;
}
