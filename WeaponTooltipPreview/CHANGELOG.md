# Changelog

## Version 0.4.0 (2026-11-18)
- Starsector 0.98.5a support.
- GraphicsLib support: shaders, lighting and distortion render inside the preview, and GraphicsLib's toggle key works while a preview is shown.
- Second-in-Command and Ship Mastery System support: refit previews include fleet-wide bonuses, and the preview engine runs as a combat simulation so campaign and battle listeners ignore it.
- Point defense previews reworked: the dummy is now a carrier (Drover, Condor or Gemini, selectable in LunaLib settings) that launches missiles and fighter wings from its own slots and bays.
- Point defense threat hotkeys (F5, F6, F7 by default, configurable in LunaLib): cycle between Missiles Only, Wings Only and Missiles & Wings, missile types (Harpoon, Swarmer, Annihilator, Reaper, Hammer), and fighter wings (Broadsword, Talon, Wasp).
- Fighter LPC weapon cycling: pressing the "cycle weapon info" key on a wing tooltip previews each of the fighter's weapons on a single fighter.
- Prewarms the preview engine on game load so the first hover opens without delay.
- Simplified sound setting to an on/off toggle at the player's sound effects volume, with a LunaLib settings icon and an "Enable logs" toggle.
- Fixed tooltip placement above the "F2 open Codex" hint, stripped ship modules on refit clones, and added shield mipmaps at low zoom.
- Codebase refactoring and cleanup.

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
