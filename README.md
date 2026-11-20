<div align="center">

<h1>Elara</h1>

<p>Pick a start and a goal, then watch BFS and DFS find their way through Minecraft, one block at a time.</p>

<p><em>Every path begins with a choice.</em></p>

<p>
  <img src="screens/hud.png" width="860" alt="Elara HUD showing the BFS search time and path length in the Backrooms">
</p>

<h2>Quick Start</h2>

<p>Drop <a href="https://github.com/kyl8/elara/releases/latest">the Elara JAR</a> into <code>mods</code>.<br>
Use <strong>Minecraft 1.20.1</strong>, <strong>Java 17</strong>, <strong>Fabric Loader</strong>, and <a href="https://modrinth.com/mod/fabric-api/versions?g=1.20.1">Fabric API</a>.</p>

<p>Mark the two points with <code>/elara origin</code> and <code>/elara finish</code>.<br>
Run <code>/elara start</code> to find a route on the same floor.</p>

<p>Switch algorithms with <code>/elara algorithm dfs</code> or <code>/elara algorithm bfs</code>.<br>
Type <code>/elara help</code> for more commands or press <strong>F6</strong> to open the route panel.</p>

<details>
  <summary>See the route panel</summary>
  <p><img src="screens/inspetor.png" width="860" alt="Elara route panel showing both marked points, the selected algorithm, and the result"></p>
</details>

<p>Try <code>/elara exit bfs</code> in <a href="https://www.planetminecraft.com/project/spinniest-s-vanilla-backrooms-map-unfinished-1-20-1/">Spinniest’s Backrooms</a> to find and walk to the Level 0 exit.</p>

<p>Build on Windows with <code>.\gradlew.bat build</code> · <a href="LICENSE">MIT</a></p>

</div>
