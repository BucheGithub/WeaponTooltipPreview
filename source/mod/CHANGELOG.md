# Changelog

## Version 0.3.0 (2026-10-30)
- Support wings with an engagement range of 0 (Xyphos, Sarissa, Mining Drone, Terminator) now fight the dummy: it is placed within reach of their weapons, and escorts hold in front of the carrier instead of behind it.
- Carriers with Defensive Targeting Array get the same treatment, as the hullmod cuts their wings' engagement range to 0.
- Threat fragment weapons (Seeker Fragment, Kinetic Fragments, Unstable Fragment, Defabrication Swarm, Voltaic Discharge, Swarm Launcher) now fire in their preview: generic hosts carry the Fragment Swarm hullmod their fragments come from.
- Version Checker support: TriOS and LunaLib can check for updates, show this changelog and download new releases.
- Mod icon in TriOS.

## Version 0.2.0 (2026-10-29)
- Windows support, alongside macOS.
- Fighter wings: wing tooltips get a preview. The ship being refitted launches the wing if it has fighter bays, otherwise a generic carrier does. Its fighters attack a dummy armed with light point defense, so some are shot down and replaced.
- The combat fighter widget under the carrier: fighters up and lost, the replacement rate, and a bar toward the next replacement.
- Battle UI readouts: the dummy's flux and hull bars and its armor hologram, as on the combat target reticle, and the weapon's ammo count and reload bar, as in the combat weapon list.
- Point defense weapons fire at the dummy and switch to the missiles and fighters it launches when they come in range.
- Built-in weapons get a preview, on a hull that has them built in.
- The split view now starts at a range of 2000.
- Ancient Mk1 battleships (Onslaught, Legion, Dominator) are previewed as themselves, and the Onslaught preview no longer flickers.
- Fixed some bugs with missiles.
- Removed the blur behind the preview.

## Version 0.1.0 (2026-10-26)
- First release: hovering a weapon shows a live combat preview inside its tooltip, with the weapon on the ship being refitted (or a generic hull) firing at a target dummy at its range, and a split view for long-range weapons.
- LunaLib settings for the preview's sound level, the dummy ship, and how the dummy uses its shield.
