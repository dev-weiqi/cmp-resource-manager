# Preview behavior and development

## Context menus

Right-click a resource or variant, or press Shift+F10 on a selection, to open its context menu. File resources use native IDE actions for Move, Copy, Rename, Safe Delete, Find Usages, Select In, and Reveal in Finder/Explorer. Refresh Preview, Copy Value, Copy Path, and Open Source are also available. Copy Value copies the resource name, or the displayed text for a values XML entry.

A resource with multiple variants first asks which version to operate on. Actions target that file only. Values XML entries omit file refactorings and Find Usages because several entries share one XML file; open the source to edit individual entries. Native action availability depends on IDE indexing and installed handlers. Find Usages does not guarantee discovery of generated CMP `Res` references.

## Resource previews

The browser has two levels. The resource list groups entries by source root and combines versions of the same resource into one row, showing a thumbnail, name, type, and version count. Click a resource or press Enter to compare its density and locale variants as cards, with thumbnails, qualifiers, filenames, and sizes. Use the back arrow or Alt+Left to return to the list.

Use the source selector, search field, and Drawable, String, String Array, Plurals, Font, and Files tabs to find resources. In the version view, double-click a version, press Enter, or click Preview to open it in the IDE. The context menu lets you copy its path. Resource subdirectories are supported, and resources with the same name in different source roots remain separate.

The browser updates automatically when the IDE reports saved resource changes, including additions, edits, renames, moves, copies, deletions, and changes to entire resource directories or project roots. Events are combined over 300 ms before a background scan. Refreshing invalidates thumbnail caches and preserves the current tab, source filter, search, and selected resource/version when it still exists. If the open resource is removed, the browser returns to the resource list. Refresh remains available as a manual fallback.

External changes follow Android Studio's filesystem synchronization. Unsaved editor text is reflected after saving; changes made outside the IDE appear when the IDE detects them, normally when switching back to Android Studio.

Values XML files are parsed into individual `string`, `string-array`, and `plurals` entries. Entries are grouped by resource key and type, even when translations use different XML filenames. The second level compares source text across locale variants, including array indices and plural quantities. Search matches keys, filenames, and text. Double-click a variant or choose Open source to navigate to its declaration line.

Text previews preserve source escape sequences and placeholders, and decode XML entities and CDATA. Each variant displays up to 4,000 characters; longer values can be opened in the editor. Values XML files are limited to 8 MiB. Invalid XML and files with DTD declarations are skipped with a warning, while other files remain available. External entities are never resolved.

Animated WebP resources show their first frame and an **Animated WebP** label in the resource list. Select a version and click **Play WebP**, or double-click it, to open a player with a checkerboard background. A single button toggles playback and pause; click Play after a finite animation ends to replay it. Closing the dialog stops playback and cancels decoding. The player uses Swing and the IDE's existing single-frame WebP decoder, without JCEF, a browser, or additional tools.

The player fits images within the available preview area while preserving their aspect ratio, and displays the original width and height in pixels. The frame counter reserves a fixed width so controls stay in place when the frame number gains a digit.

Animation previews preserve frame positions, transparency, blending flags, disposal regions, and loop counts. Frame durations have a 10 ms minimum, and compositing uses Java2D sRGB. The canvas starts transparent, and disposed regions return to transparency, matching libwebp animation previews. The optional ANIM background color hint is ignored to avoid opaque borders around transparent frames. To limit IDE memory use, previews allow up to 1,000 frames and 128 MiB of scaled frame data; larger animations display an explanatory message.

Vector Drawable XML files show rendered thumbnails in both the resource list and variant cards. Click Preview or double-click a variant to open a bounded preview with a transparency checkerboard, preserved aspect ratio, and original XML dimensions. Open source is available in the preview and the variant context menu. Rendering uses Android Studio's bundled vector renderer, including paths, fills, strokes, group transforms, and clipping.

Vector previews support standalone `<vector>` XML with literal dimensions and colors. Resource references such as `@color/...`, theme references, and other drawable XML types such as selectors or shapes are not resolved. Unsupported or malformed XML retains a fallback icon and can be opened as source. Vector XML is limited to 1 MiB, 4,096 elements, and 64 nesting levels; DTDs and external entities are disabled.

Raster image decoding uses the IDE's ImageIO and WebP support. Unsupported formats and images exceeding 32 MiB or 32 million pixels display a file icon and can still be opened as source files. Resource directories with custom names are not yet supported.

## Build and verify

Requires JDK 21, Android Studio, and an internet connection. A Gradle wrapper is included.

```sh
./gradlew buildPlugin check verifyPluginProjectConfiguration verifyPlugin
```

On macOS, the default SDK location is `/Applications/Android Studio.app`. To use another installation:

```sh
./gradlew buildPlugin check -PstudioPath="/path/to/android-studio"
```

The ZIP is generated in `build/distributions/`. The `check` task runs ktlint and smoke checks for resource discovery, VFS event filtering and debounce, listener disposal, stable resource identity, grouping, source root isolation, localized XML entries, XML safety, vector rendering and bounds, source lines, text rendering, version metadata, WebP frame compositing, transparent disposal, and playback/pause without launching the IDE. To also check resources, vector XML, and an animated WebP in a real CMP project:

```sh
./gradlew smokeCheck -PresourceProject="/path/to/compose-project"
```

The `verifyPlugin` task checks binary compatibility against the local Android Studio installation, or the installation selected with `-PstudioPath`. Reports are written to `build/reports/pluginVerifier/`. This does not verify every IDE version allowed by the plugin's minimum build number.

To launch a separate Android Studio development instance:

```sh
./gradlew runIde
```
