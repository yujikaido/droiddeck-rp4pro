import json
import os

CONTROLS = "/etc/droiddeck-device-controls"
REQUESTS = ("wifi", "bluetooth")


class Plugin:
    async def state(self):
        try:
            with open(os.path.join(CONTROLS, "state.json")) as source:
                return json.load(source).get("rp4pro")
        except (OSError, ValueError):
            return None

    async def request(self, name="", value=""):
        if name not in REQUESTS or value not in ("0", "1"):
            return False
        path = os.path.join(CONTROLS, name + ".request")
        with open(path + ".tmp", "w") as target:
            target.write(value + "\n")
        os.replace(path + ".tmp", path)
        return True
