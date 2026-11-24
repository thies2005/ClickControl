#!/usr/bin/env python3
"""
Native Linux StatusNotifierItem + DBusMenu tray host for ClickControl.
Uses GI Gio/GLib + transparent vector SVGs so Ubuntu GNOME AppIndicators renders a
sharp, resolution-independent top-bar icon without X11 white-box clipping.
"""
import json
import os
import shutil
import signal
import sys
import tempfile
import threading

import gi
gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")
from gi.repository import Gio, GLib

try:
    gi.require_version("GdkPixbuf", "2.0")
    from gi.repository import GdkPixbuf
except Exception:
    GdkPixbuf = None

SNI_XML = """
<node>
  <interface name="org.kde.StatusNotifierItem">
    <property name="Category" type="s" access="read"/>
    <property name="Id" type="s" access="read"/>
    <property name="Title" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="WindowId" type="i" access="read"/>
    <property name="IconThemePath" type="s" access="read"/>
    <property name="Menu" type="o" access="read"/>
    <property name="ItemIsMenu" type="b" access="read"/>
    <property name="IconName" type="s" access="read"/>
    <property name="IconPixmap" type="a(iiay)" access="read"/>
    <property name="OverlayIconName" type="s" access="read"/>
    <property name="OverlayIconPixmap" type="a(iiay)" access="read"/>
    <property name="AttentionIconName" type="s" access="read"/>
    <property name="AttentionIconPixmap" type="a(iiay)" access="read"/>
    <property name="AttentionMovieName" type="s" access="read"/>
    <property name="IconAccessibleDesc" type="s" access="read"/>
    <method name="ContextMenu">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="Activate">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="ProvideXdgActivationToken">
      <arg name="token" type="s" direction="in"/>
    </method>
    <method name="SecondaryActivate">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="XAyatanaSecondaryActivate">
      <arg name="timestamp" type="u" direction="in"/>
    </method>
    <method name="Scroll">
      <arg name="delta" type="i" direction="in"/>
      <arg name="orientation" type="s" direction="in"/>
    </method>
    <signal name="NewTitle"/>
    <signal name="NewIcon"/>
    <signal name="NewAttentionIcon"/>
    <signal name="NewOverlayIcon"/>
    <signal name="NewMenu"/>
    <signal name="NewStatus">
      <arg name="status" type="s"/>
    </signal>
  </interface>
</node>
"""

MENU_XML = """
<node>
  <interface name="com.canonical.dbusmenu">
    <property name="Version" type="u" access="read"/>
    <property name="TextDirection" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="IconThemePath" type="as" access="read"/>
    <method name="GetLayout">
      <arg type="i" name="parentId" direction="in"/>
      <arg type="i" name="recursionDepth" direction="in"/>
      <arg type="as" name="propertyNames" direction="in"/>
      <arg type="u" name="revision" direction="out"/>
      <arg type="(ia{sv}av)" name="layout" direction="out"/>
    </method>
    <method name="GetGroupProperties">
      <arg type="ai" name="ids" direction="in"/>
      <arg type="as" name="propertyNames" direction="in"/>
      <arg type="a(ia{sv})" name="properties" direction="out"/>
    </method>
    <method name="GetProperty">
      <arg type="i" name="id" direction="in"/>
      <arg type="s" name="name" direction="in"/>
      <arg type="v" name="value" direction="out"/>
    </method>
    <method name="Event">
      <arg type="i" name="id" direction="in"/>
      <arg type="s" name="eventId" direction="in"/>
      <arg type="v" name="data" direction="in"/>
      <arg type="u" name="timestamp" direction="in"/>
    </method>
    <method name="EventGroup">
      <arg type="a(isvu)" name="events" direction="in"/>
      <arg type="ai" name="idErrors" direction="out"/>
    </method>
    <method name="AboutToShow">
      <arg type="i" name="id" direction="in"/>
      <arg type="b" name="needUpdate" direction="out"/>
    </method>
    <method name="AboutToShowGroup">
      <arg type="ai" name="ids" direction="in"/>
      <arg type="ai" name="updatesNeeded" direction="out"/>
      <arg type="ai" name="idErrors" direction="out"/>
    </method>
    <signal name="ItemsPropertiesUpdated">
      <arg type="a(ia{sv})" name="updatedProps" direction="out"/>
      <arg type="a(ias)" name="removedProps" direction="out"/>
    </signal>
    <signal name="LayoutUpdated">
      <arg type="u" name="revision" direction="out"/>
      <arg type="i" name="parent" direction="out"/>
    </signal>
    <signal name="ItemActivationRequested">
      <arg type="i" name="id" direction="out"/>
      <arg type="u" name="timestamp" direction="out"/>
    </signal>
  </interface>
</node>
"""


def build_svg(state: str) -> bytes:
    fill = {"idle": "#CFD5DE", "active": "#FFC476", "connected": "#F6F8FC"}[state]
    outline = "#272C33"
    badge = (
        f'<circle cx="19.5" cy="19.5" r="2.5" fill="#2EC27E" stroke="{outline}" stroke-width="1.5"/>'
        if state == "connected" else ""
    )
    return f"""<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 24 24">
  <path d="M9 3H15V9H21V15H15V21H9V15H3V9H9Z" fill="{fill}"
        stroke="{outline}" stroke-width="1.5" stroke-linejoin="round"/>
  <circle cx="12" cy="12" r="1.6" fill="{outline}"/>
  {badge}
</svg>""".encode("utf-8")


def svg_to_argb_pixmap(svg_bytes: bytes, size: int = 48):
    if GdkPixbuf is None:
        return []
    loader = GdkPixbuf.PixbufLoader.new_with_type("svg")
    loader.set_size(size, size)
    loader.write(svg_bytes)
    loader.close()
    pb = loader.get_pixbuf()
    w, h = pb.get_width(), pb.get_height()
    stride = pb.get_rowstride()
    n_ch = pb.get_n_channels()
    pixels = pb.get_pixels()
    out = bytearray(w * h * 4)
    idx = 0
    for y in range(h):
        row_off = y * stride
        for x in range(w):
            p = row_off + x * n_ch
            r = pixels[p]
            g = pixels[p + 1]
            b = pixels[p + 2]
            a = pixels[p + 3] if n_ch == 4 else 255
            # SNI IconPixmap uses network byte order (big-endian ARGB32)
            out[idx] = a
            out[idx + 1] = r
            out[idx + 2] = g
            out[idx + 3] = b
            idx += 4
    return [(w, h, bytes(out))]


class ClickControlSniHost:
    def __init__(self, loop: GLib.MainLoop):
        self.loop = loop
        self.icon_dir = tempfile.mkdtemp(prefix="clickcontrol-sni-")
        os.chmod(self.icon_dir, 0o700)
        scalable = os.path.join(self.icon_dir, "hicolor", "scalable", "status")
        apps_dir = os.path.join(self.icon_dir, "hicolor", "scalable", "apps")
        os.makedirs(scalable, exist_ok=True)
        os.makedirs(apps_dir, exist_ok=True)

        self.pixmaps = {}
        self.icon_names = {
            "idle": "clickcontrol-idle",
            "active": "clickcontrol-active",
            "connected": "clickcontrol-connected",
        }
        for key, name in self.icon_names.items():
            svg = build_svg(key)
            for target in (
                os.path.join(self.icon_dir, f"{name}.svg"),
                os.path.join(scalable, f"{name}.svg"),
                os.path.join(apps_dir, f"{name}.svg"),
            ):
                with open(target, "wb") as f:
                    f.write(svg)
            self.pixmaps[key] = svg_to_argb_pixmap(svg, 48)

        # Minimal hicolor index.theme so GTK IconTheme searches cleanly
        with open(os.path.join(self.icon_dir, "index.theme"), "w", encoding="utf-8") as f:
            f.write("[Icon Theme]\nName=ClickControl\nDirectories=hicolor/scalable/status,hicolor/scalable/apps\n\n"
                    "[hicolor/scalable/status]\nSize=48\nType=Scalable\n\n"
                    "[hicolor/scalable/apps]\nSize=48\nType=Scalable\n")

        self.state = {
            "running": False,
            "connected": False,
            "bleLabel": "Stopped",
            "activePresetId": "",
            "hudEnabled": False,
            "presets": [],
        }
        self.revision = 1
        self.menu_items = {}
        self.menu_order = []
        self.item_actions = {}
        self._rebuild_menu()

        self.conn = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        watcher_owner = self.conn.call_sync(
            "org.freedesktop.DBus",
            "/org/freedesktop/DBus",
            "org.freedesktop.DBus",
            "GetNameOwner",
            GLib.Variant("(s)", ("org.kde.StatusNotifierWatcher",)),
            GLib.VariantType("(s)"),
            Gio.DBusCallFlags.NONE,
            1500,
            None,
        )
        if not watcher_owner:
            raise RuntimeError("No StatusNotifierWatcher owner")

        self.service_name = f"org.kde.StatusNotifierItem-{os.getpid()}-1"
        self.sni_info = Gio.DBusNodeInfo.new_for_xml(SNI_XML).interfaces[0]
        self.menu_info = Gio.DBusNodeInfo.new_for_xml(MENU_XML).interfaces[0]

        self.owner_id = Gio.bus_own_name_on_connection(
            self.conn, self.service_name, Gio.BusNameOwnerFlags.NONE, None, None
        )
        self.sni_reg = self.conn.register_object(
            "/StatusNotifierItem",
            self.sni_info,
            self._sni_call,
            self._sni_get_prop,
            None,
        )
        self.menu_reg = self.conn.register_object(
            "/MenuBar",
            self.menu_info,
            self._menu_call,
            self._menu_get_prop,
            None,
        )
        self.conn.call_sync(
            "org.kde.StatusNotifierWatcher",
            "/StatusNotifierWatcher",
            "org.kde.StatusNotifierWatcher",
            "RegisterStatusNotifierItem",
            GLib.Variant("(s)", (self.service_name,)),
            None,
            Gio.DBusCallFlags.NONE,
            2000,
            None,
        )

    def _icon_key(self) -> str:
        if not self.state.get("running"):
            return "idle"
        return "connected" if self.state.get("connected") else "active"

    def _emit_action(self, action_line: str):
        print(f"ACTION {action_line}", flush=True)

    def _rebuild_menu(self):
        self.revision += 1
        items = {}
        order = []
        actions = {}
        next_id = 1

        def add_item(label: str, action: str | None = None, sep: bool = False,
                     toggle_type: str | None = None, toggle_state: int | None = None):
            nonlocal next_id
            item_id = next_id
            next_id += 1
            props = {}
            if sep:
                props["type"] = GLib.Variant("s", "separator")
            else:
                props["type"] = GLib.Variant("s", "standard")
                props["label"] = GLib.Variant("s", label)
                props["enabled"] = GLib.Variant("b", True)
                props["visible"] = GLib.Variant("b", True)
                if toggle_type:
                    props["toggle-type"] = GLib.Variant("s", toggle_type)
                    props["toggle-state"] = GLib.Variant("i", int(toggle_state or 0))
            items[item_id] = props
            order.append(item_id)
            if action:
                actions[item_id] = action

        add_item("Open ClickControl", "show")
        ble_label = self.state.get("bleLabel") or ("Active" if self.state.get("running") else "Stopped")
        bridge_text = f"Stop Bridge ({ble_label})" if self.state.get("running") else "Start Bridge"
        add_item(bridge_text, "toggle_bridge")

        presets = self.state.get("presets") or []
        if presets:
            add_item("", sep=True)
            active_id = self.state.get("activePresetId") or ""
            for p in presets:
                pid = str(p.get("id", ""))
                pname = str(p.get("name", "Preset"))
                selected = 1 if pid == active_id else 0
                add_item(pname, f"select_preset {pid}", toggle_type="radio", toggle_state=selected)

        add_item("", sep=True)
        hud_label = "Hide Floating HUD" if self.state.get("hudEnabled") else "Show Floating HUD"
        add_item(hud_label, "toggle_hud")
        add_item("", sep=True)
        add_item("Quit ClickControl", "quit")

        self.menu_items = items
        self.menu_order = order
        self.item_actions = actions

    def apply_state(self, new_state: dict):
        self.state = new_state
        self._rebuild_menu()
        self.conn.emit_signal(
            None, "/StatusNotifierItem", "org.kde.StatusNotifierItem", "NewIcon", None
        )
        self.conn.emit_signal(
            None, "/StatusNotifierItem", "org.kde.StatusNotifierItem", "NewTitle", None
        )
        self.conn.emit_signal(
            None,
            "/MenuBar",
            "com.canonical.dbusmenu",
            "LayoutUpdated",
            GLib.Variant("(ui)", (self.revision, 0)),
        )

    def _sni_get_prop(self, _conn, _sender, _path, _iface, prop_name):
        icon_key = self._icon_key()
        icon_name = self.icon_names[icon_key]
        status_label = self.state.get("bleLabel") or ("Running" if self.state.get("running") else "Stopped")
        if prop_name == "Category":
            return GLib.Variant("s", "ApplicationStatus")
        if prop_name == "Id":
            return GLib.Variant("s", "clickcontrol")
        if prop_name == "Title":
            return GLib.Variant("s", f"ClickControl — {status_label}")
        if prop_name == "Status":
            return GLib.Variant("s", "Active")
        if prop_name == "WindowId":
            return GLib.Variant("i", 0)
        if prop_name == "IconThemePath":
            return GLib.Variant("s", self.icon_dir)
        if prop_name == "Menu":
            return GLib.Variant("o", "/MenuBar")
        if prop_name == "ItemIsMenu":
            return GLib.Variant("b", False)
        if prop_name == "IconName":
            return GLib.Variant("s", icon_name)
        if prop_name == "IconPixmap":
            return GLib.Variant("a(iiay)", self.pixmaps.get(icon_key, []))
        if prop_name in ("OverlayIconName", "AttentionIconName", "AttentionMovieName"):
            return GLib.Variant("s", "")
        if prop_name in ("OverlayIconPixmap", "AttentionIconPixmap"):
            return GLib.Variant("a(iiay)", [])
        if prop_name == "IconAccessibleDesc":
            return GLib.Variant("s", f"ClickControl ({status_label})")
        return None

    def _sni_call(self, _conn, _sender, _path, _iface, method_name, _params, invocation):
        if method_name in ("Activate", "SecondaryActivate", "XAyatanaSecondaryActivate"):
            self._emit_action("show")
        invocation.return_value(None)

    def _menu_get_prop(self, _conn, _sender, _path, _iface, prop_name):
        if prop_name == "Version":
            return GLib.Variant("u", 3)
        if prop_name == "TextDirection":
            return GLib.Variant("s", "ltr")
        if prop_name == "Status":
            return GLib.Variant("s", "normal")
        if prop_name == "IconThemePath":
            return GLib.Variant("as", [])
        return None

    def _filter_props(self, props: dict, prop_names):
        if not prop_names:
            return props
        wanted = set(prop_names)
        return {k: v for k, v in props.items() if k in wanted}

    def _menu_node(self, item_id: int, depth: int, prop_names):
        if item_id == 0:
            root_props = self._filter_props(
                {"children-display": GLib.Variant("s", "submenu")}, prop_names
            )
            children = []
            if depth != 0:
                child_depth = -1 if depth < 0 else depth - 1
                for cid in self.menu_order:
                    children.append(
                        GLib.Variant("(ia{sv}av)", self._menu_node(cid, child_depth, prop_names))
                    )
            return (0, root_props, children)
        props = self._filter_props(self.menu_items.get(item_id, {}), prop_names)
        return (item_id, props, [])

    def _menu_call(self, _conn, _sender, _path, _iface, method_name, params, invocation):
        if method_name == "GetLayout":
            parent_id, depth, prop_names = params.unpack()
            layout = self._menu_node(parent_id, depth, prop_names)
            invocation.return_value(GLib.Variant("(u(ia{sv}av))", (self.revision, layout)))
        elif method_name == "GetGroupProperties":
            ids, prop_names = params.unpack()
            result = []
            for item_id in ids:
                if item_id == 0:
                    props = self._filter_props({"children-display": GLib.Variant("s", "submenu")}, prop_names)
                    result.append((0, props))
                elif item_id in self.menu_items:
                    result.append((item_id, self._filter_props(self.menu_items[item_id], prop_names)))
            invocation.return_value(GLib.Variant("(a(ia{sv}))", (result,)))
        elif method_name == "GetProperty":
            item_id, prop = params.unpack()
            props = {"children-display": GLib.Variant("s", "submenu")} if item_id == 0 else self.menu_items.get(item_id, {})
            val = props.get(prop, GLib.Variant("s", ""))
            invocation.return_value(GLib.Variant("(v)", (val,)))
        elif method_name == "Event":
            item_id, event_id, _data, _ts = params.unpack()
            if event_id == "clicked" and item_id in self.item_actions:
                self._emit_action(self.item_actions[item_id])
            invocation.return_value(None)
        elif method_name == "EventGroup":
            events = params.unpack()[0]
            for item_id, event_id, _data, _ts in events:
                if event_id == "clicked" and item_id in self.item_actions:
                    self._emit_action(self.item_actions[item_id])
            invocation.return_value(GLib.Variant("(ai)", ([],)))
        elif method_name == "AboutToShow":
            invocation.return_value(GLib.Variant("(b)", (False,)))
        elif method_name == "AboutToShowGroup":
            invocation.return_value(GLib.Variant("(aiai)", ([], [])))
        else:
            invocation.return_value(None)

    def cleanup(self):
        try:
            self.conn.unregister_object(self.sni_reg)
            self.conn.unregister_object(self.menu_reg)
            Gio.bus_unown_name(self.owner_id)
        except Exception:
            pass
        shutil.rmtree(self.icon_dir, ignore_errors=True)


def main():
    loop = GLib.MainLoop()
    try:
        host = ClickControlSniHost(loop)
    except Exception as exc:
        print(f"ERROR {exc}", file=sys.stderr, flush=True)
        sys.exit(1)

    def stdin_reader():
        try:
            for raw in sys.stdin:
                line = raw.strip()
                if not line:
                    continue
                try:
                    data = json.loads(line)
                    GLib.idle_add(lambda d=data: (host.apply_state(d), False)[1])
                except Exception:
                    pass
        finally:
            GLib.idle_add(loop.quit)

    signal.signal(signal.SIGTERM, lambda *_: GLib.idle_add(loop.quit))
    threading.Thread(target=stdin_reader, daemon=True).start()
    print("READY", flush=True)
    try:
        loop.run()
    finally:
        host.cleanup()


if __name__ == "__main__":
    main()
