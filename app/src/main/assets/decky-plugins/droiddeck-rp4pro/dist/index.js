// Decky legacy plugin: the loader evals this file and calls the returned function with its serverAPI.
(function () {
  "use strict";
  const React = window.SP_REACT;
  const h = React.createElement;
  const ui = window.DFL || {};
  const plain = (tag) => (props) => h(tag, { style: { padding: "4px 0" } }, props.label, props.children);
  const PanelSection = ui.PanelSection || plain("div");
  const PanelSectionRow = ui.PanelSectionRow || plain("div");
  const Field = ui.Field || ((props) => h("div", null, h("b", null, props.label), " ", props.children,
    props.description ? h("div", null, props.description) : null));
  const ButtonItem = ui.ButtonItem || ((props) => h("button", { onClick: props.onClick }, props.children));
  const ToggleField = ui.ToggleField || ((props) => h("label", null,
    h("input", { type: "checkbox", checked: !!props.checked, onChange: (e) => props.onChange(e.target.checked) }), " ", props.label));

  function Content(props) {
    const api = props.serverAPI;
    const [state, setState] = React.useState(null);
    const [loaded, setLoaded] = React.useState(false);
    const load = async () => {
      try {
        const reply = await api.callPluginMethod("state", {});
        if (reply.success) setState(reply.result);
      } finally {
        setLoaded(true);
      }
    };
    React.useEffect(() => {
      load();
      const timer = setInterval(load, 1000);
      return () => clearInterval(timer);
    }, []);
    const send = async (name, on) => {
      await api.callPluginMethod("request", { name: name, value: on ? "1" : "0" });
      setTimeout(load, 500);
    };

    if (!state) {
      return h(PanelSection, null, h(PanelSectionRow, null,
        h(Field, { label: loaded ? "RP4 Pro controls are not available" : "Loading…",
          description: loaded ? "Start Steam from DroidDeck on a Retroid Pocket 4 Pro." : null })));
    }
    return h(React.Fragment, null,
      h(PanelSection, { title: "Wireless" },
        h(PanelSectionRow, null, h(ToggleField, { label: "Wi-Fi", checked: !!state.wifi, onChange: (on) => send("wifi", on) })),
        h(PanelSectionRow, null, h(ToggleField, { label: "Bluetooth", checked: !!state.bluetooth, onChange: (on) => send("bluetooth", on) }))));
  }

  function Icon() {
    return h("svg", { viewBox: "0 0 24 24", width: "1em", height: "1em", fill: "none", stroke: "currentColor", strokeWidth: 2,
      strokeLinecap: "round", strokeLinejoin: "round" },
      h("path", { d: "M2 8.5a15 15 0 0 1 20 0" }),
      h("path", { d: "M5 12a10 10 0 0 1 14 0" }),
      h("path", { d: "M8.5 15.5a5 5 0 0 1 7 0" }),
      h("circle", { cx: 12, cy: 19, r: 1 }));
  }

  return function (serverAPI) {
    return {
      title: h("div", { className: ui.staticClasses ? ui.staticClasses.Title : undefined }, "DroidDeck RP4 Pro"),
      content: h(Content, { serverAPI: serverAPI }),
      icon: h(Icon),
      onDismount() {},
    };
  };
})()
