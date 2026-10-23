# Backups

Light Phone users can back their data up to their own cloud storage (Google Drive, Dropbox, WebDAV, etc). 
Backups run automatically in the background, on a schedule the user picks, and cover both LightOS's built-in tools (Messages, Album) and **community tools that opt in**.

This shows how to make your tool's data backup-able, how to test it in the emulator, and how it's laid out on the cloud side.

## Enabling backups for your tool

Backups piggyback on the [Tool Manager integration](../tool_manager): anything you expose through a Tool Manager file directory can be backed up by flagging it.

1. Make your tool Tool Manager capable (`capabilities = ["tool-manager-provider"]` in `lighttool.toml` and a `getToolManagerManifest()` override, see the [Tool Manager docs](../tool_manager)).
2. Set `canBeBackedUp = true` on each `ClientLeafNode` whose files should be backed up:
   ```kotlin
   override fun getToolManagerManifest(): ClientToolManifest {
       return ClientToolManifest(
           title = "Your Tool's Name",
           roots = listOf(
               ClientLeafNode(
                   FileBrowserSpec(label = "Notes", path = "notes"),
                   canBeBackedUp = true,
               ),
               // not backed up (the default)
               ClientLeafNode(FileBrowserSpec(label = "Cache", path = "cache")),
           )
       )
   }
   ```
   The [Tool Manager demo](../../examples/tool-manager-demo) does exactly this.
3. Read and write the data you want backed up through **`LightFileShare`** (`lightContext.fileShare`, see the [client docs](../../sdk/client)), under the same `path` you gave the leaf. `LightFileShare` is rooted at your tool's `files/shared` directory, which is the only place LightOS (and so the Tool Manager and backups) can see. Anything you keep outside it, in your tool's other private storage or in a database, is **not** backed up.
   ```kotlin
   // lands in files/shared/notes/todo.txt, which the "notes" leaf above backs up
   lightContext.fileShare.write("notes/todo.txt") { it.write("buy milk") }
   ```
   Writing through `LightFileShare` also keeps the file's modified time current, which is what backups go by (see below).

### What gets backed up

* Every file under the leaf's directory in `files/shared`, including files in subdirectories. Hidden files are skipped unless the leaf sets `showHiddenFiles = true`.
* Files are chosen by **last-modified time**: each run uploads files that changed since the last successful backup of that leaf. If you rewrite a file, touch its modified time or it won't be re-uploaded; a file with an old modified time that's newly *added* (e.g. restored from elsewhere) is not picked up until its modified time moves.
* Deleting a file locally does **not** delete it from the cloud. Backups are append-only history.

### The user has to opt in, per tool

Backups for third-party tools are **off by default**. After a user links a provider and turns backups on, they have to open **Settings > Backups > Sources** and switch your tool on. (LightOS's own sources, Messages and Album, are on by default.) Tell your users where to find it, and don't assume a tool with `canBeBackedUp` is being backed up.

## Where your data lands

Under the backup folder (named `backup` in the emulator) each backable leaf gets a folder named `<your authority>::<leaf path>`, and each run writes one subfolder per day of changes, named with that window's end time (UTC):

```
backup/
  com.example.yourtool::notes/
    2026-10-13T18-41-07Z/
      todo.txt
      ideas.md
      checksums.sha256       # SHA-256 of each file in this folder
    _meta/
      2026-10-13T18-41-07Z-3fa9c1d2   # marks the window as complete
```

Things to know:

* Time is cut into one-day windows, and only windows that actually contain changes get a folder. A first backup of old data can therefore produce several folders (one per day that has changes).
* File names within a window are the file names from your directory. Relative sub-paths are not preserved in the cloud layout.
* A window with an unusually large number of files may be split over more than one run; the remainder is picked up next time.

## Testing in the emulator

Make sure you've set up the emulator as a [system app](../system_app). **Also, ensure you've setup adb to forward ports from your local machine so your browser can talk to the Tool Manager and the emulator's embedded server** (which acts as your fake cloud storage provider in this case):
```bash
adb forward tcp:8090 tcp:8090
adb forward tcp:54449 tcp:54449
```


1. Install your tool with `canBeBackedUp = true` and put some files in its Tool Manager directory.
2. In the emulator, open **Settings > Backups > Provider**. This opens the Tool Manager page for the dummy provider; tap **Authorize** and finish in the browser flow. Back in Backups, the provider should now read `Custom`. (By default this signs in through a shared development OAuth relay; set `backupWorkerHost` in `local.properties` to use your own.)
3. Turn **Backups Enabled** on, then open **Sources** and switch your tool on.
4. Open **Last Run** and tap **Run Backup Now**.
5. The result appears under **Last Run**. The uploaded files are stored in the emulator app's private storage at `files/fileserver/backup/...` on the emulator.

The Wi-Fi and charging constraints are passed to Android's scheduler as usual, but an emulator is effectively always charging and connected, so they won't ever hold a backup back there. Also note Android won't run a periodic job more often than every 15 minutes, so use **Run Backup Now** while developing, not the nightly schedule.

