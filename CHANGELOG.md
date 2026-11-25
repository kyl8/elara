# Changelog

## 0.4.0

- World scans now start near the marked points and grow toward reachable boundaries when a detour is needed. Each column is read once per scan, and all algorithms use the same completed graph.
- The horizontal side limit and the number of unique `(x, y, z)` states are independent. New installations allow 128 blocks per side, 32,768 states and 262,144 candidate positions per scan. Existing custom limits can be updated with `/elara limits 128 32768`.
- Scans run in short client-tick batches and use loaded chunks only. `/elara status` reports progress, while the result includes dimensions, state and column counts, stages and elapsed time.
- Automatic bounds use up to six blocks of vertical margin around the selected heights. Use manual region corners for higher detours or to compare routes across a wider area.
- GitHub Actions builds and tests each change. Matching version tags publish the verified Fabric JAR.

Install `elara-0.4.0.jar` in `mods` for Minecraft 1.20.1 with Java 17, Fabric Loader 0.16.14 or later and Fabric API for 1.20.1.
