# PocketTk v4.1.2

## Fixes
- `pockettk check` now does what it says: it shows the warnings (and a summary) instead of printing the whole generated Java file. `check -v` still prints the Java. This applies to the command line on Windows, Linux and macOS and to Termux on Android; the window's `check` was already right.
- The generated Java's header comment no longer names the old project (ApkApple).

Everything from v4.1.1 is still in: the terminal-style window for macOS, Windows and Linux, more of tkinter (images, Canvas, Treeview), and PocketTk-4.1.2-android.zip (the KernelSU module and the unrooted Termux files). See CHANGELOG.md for the details.

## Not tested on real systems yet
The window on real Mac, Windows and Linux desktops, the installer and AppImage, and #use: termux-connect on a phone were only tested with stand-ins here.
