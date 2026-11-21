#!/system/bin/sh
. "$MODPATH/logo.sh"
pockettk_logo ui_print
ui_print "- PocketTk v4.1.2: tkinter -> APK"
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/apkapple.sh" 0 0 0755
set_perm "$MODPATH/install_termux.sh" 0 0 0755
set_perm "$MODPATH/system/bin/pockettk" 0 2000 0755 u:object_r:system_file:s0
set_perm "$MODPATH/system/bin/tkdroid" 0 2000 0755 u:object_r:system_file:s0
set_perm "$MODPATH/system/bin/apkapple" 0 2000 0755 u:object_r:system_file:s0
set_perm "$MODPATH/system/bin/apple" 0 2000 0755 u:object_r:system_file:s0
. "$MODPATH/migrate.sh"
pockettk_migrate_all                       # ApkApple folders and data -> PocketTk
mkdir -p /data/adb/pockettk
rm -f /data/adb/pockettk/ids/pockettk_app 2>/dev/null
# the cut-down modules (PocketTk Demo, PocketTk Consumer) must not stay next to the full one (they would take over the commands)
for m in tkdroid_demo tkdroid_consumer pockettk_demo pockettk_consumer; do
  if [ -d /data/adb/modules/$m ]; then touch /data/adb/modules/$m/remove; ui_print "- $m will be removed at the next reboot"; fi
done
sh "$MODPATH/install_termux.sh" "$MODPATH"
pm uninstall pockettk.ssdes.pockettk_app >/dev/null 2>&1 && ui_print "- removed the old PocketTk window app (Android has no window any more)"
[ -x /data/data/com.termux/files/usr/bin/pockettk ] && ui_print "- Termux commands installed: pockettk (the old names tkdroid, apkapple and apple are deprecated; use pockettk)"
ui_print "- Usage (normal Termux user, NOT tsu):  pockettk run app.py"
ui_print "- First run: pockettk doctor"
ui_print "- No reboot needed"
