package wtp.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.combat.ArmorGridAPI;
import com.fs.starfarer.api.combat.FighterLaunchBayAPI;
import com.fs.starfarer.api.combat.FighterWingAPI;
import com.fs.starfarer.api.combat.FluxTrackerAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipAPI.HullSize;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.fleet.FleetMemberType;
import com.fs.starfarer.api.graphics.SpriteAPI;
import com.fs.starfarer.api.loading.FighterWingSpecAPI;
import com.fs.starfarer.api.loading.FormationType;
import org.lazywizard.lazylib.ui.FontException;
import org.lazywizard.lazylib.ui.LazyFont;
import org.lwjgl.opengl.GL11;
import wtp.Log;
import wtp.bridge.ObfBridge;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The preview's readouts, styled after the battle UI and drawn in UI coordinates over the scene. The dummy gets the
 * target reticle's FLUX and HULL bars in the top right corner and the armor hologram in the bottom right. Under the
 * host, a weapon gets its line from the weapon list (name, ammo, reload bar), a carrier the fighter widget. Same fonts
 * and colours as in combat, laid out to fit the panel (the game's own reticle sprawls far beyond it).
 * Callers set up blending and leave texturing off.
 */
final class PreviewHud {

    /** The combat HUD's font (target reticle, weapon list). */
    private static final String FONT_PATH = "graphics/fonts/victor10.fnt";
    /** Distance from the pane edges. */
    private static final float MARGIN = 6f;
    private static final float PAD = 4f;
    private static final float GAP = 6f;
    private static final float LABEL_W = 34f;
    private static final float BAR_MAX_W = 90f;
    private static final float BAR_MIN_W = 30f;
    private static final float BAR_H = 6f;
    private static final float AMMO_W = 30f;
    private static final float RELOAD_W = 22f;
    private static final float RELOAD_H = 8f;
    private static final float PANEL_ALPHA = 0.55f;

    private static final Color ENEMY = settingsColor("textEnemyColor", new Color(255, 100, 0));
    private static final Color FRIEND = settingsColor("textFriendColor", new Color(155, 255, 0));

    /**
     * The armor hologram: the game's hull schematic widget (the one on the target reticle), with its textures, colours
     * and constants. The reticle's box is 125 UI units for a capital ship, scaled by hull size (a destroyer's is 75);
     * the preview draws it at two thirds of that.
     */
    private static final float SCHEMATIC_CAPITAL_SIZE = 125f;
    private static final float SCHEMATIC_SCALE = 2f / 3f;
    private static final String SPOTLIGHT_PATH = "graphics/hud/spotlight.png";
    private static final Color SPOTLIGHT_COLOR = new Color(255, 255, 0, 191);
    private static final Color GRID_COLOR = new Color(255, 210, 0);
    private static final int CELL_ALPHA = 102;

    /**
     * The combat fighter widget: a square slot per wing, its icon drawn 12 units wider (fighters in formation overhang
     * the slot a little), and, added here, a bar toward the next replacement. Each fighter is its hull tinted with the
     * widget colour (0.4 alpha when up and running, 0.2 when lost), its weapons, and a hologram over it: the
     * {@code grid_small} grid seen through a {@code spotlight_small} mask, at full strength when up and half when lost,
     * and, when up, the spotlight itself in the widget colour.
     */
    private static final float WING_ICON = 40f;
    private static final float ICON_OVERHANG = 12f;
    private static final float FIGHTER_ALIVE_ALPHA = 0.4f;
    private static final float FIGHTER_LOST_ALPHA = 0.2f;
    private static final float HOLOGRAM_LOST_ALPHA = 0.5f;
    private static final String ICON_SPOTLIGHT_PATH = "graphics/hud/spotlight_small.png";
    private static final String ICON_GRID_PATH = "graphics/hud/grid_small.png";
    private static final float COOLDOWN_H = 4f;
    /**
     * Fighter positions in the icon by wing size (x, y pairs, -0.5..0.5 of the icon), the game's tables for box, claw
     * and V formations and for all others. Wings past six fighters show their first six, as in the game.
     */
    private static final float[][] FORMATION_BOX = {{}, {0f, 0f}, {-0.35f, 0f, 0.35f, 0f}, {0f, 0f, -0.45f, 0f, 0.45f, 0f},
            {-0.4f, 0.4f, -0.4f, -0.4f, 0.4f, 0.4f, 0.4f, -0.4f},
            {0f, -0.3f, -0.45f, -0.3f, 0.45f, -0.3f, -0.25f, 0.33f, 0.25f, 0.33f},
            {0f, 0.25f, -0.5f, 0.25f, 0.5f, 0.25f, 0f, -0.25f, -0.5f, -0.25f, 0.5f, -0.25f}};
    private static final float[][] FORMATION_CLAW = {{}, {0f, 0f}, {-0.35f, 0.1f, 0.35f, 0f},
            {0f, -0.33f, -0.33f, 0.2f, 0.33f, 0.2f}, {-0.45f, 0.5f, -0.35f, 0f, 0.45f, 0f, 0.05f, -0.4f},
            {0f, 0.05f, -0.45f, 0.3f, 0.45f, 0.3f, -0.35f, -0.33f, 0.35f, -0.33f},
            {0f, 0.15f, -0.4f, 0.25f, 0.4f, 0.25f, 0f, -0.4f, -0.4f, -0.25f, 0.4f, -0.25f}};
    private static final float[][] FORMATION_V = {{}, {0f, 0f}, {-0.35f, 0f, 0.35f, 0.1f},
            {0f, 0.33f, -0.33f, -0.2f, 0.33f, -0.2f}, {0.15f, 0.45f, -0.2f, 0.05f, 0.5f, 0.05f, -0.5f, -0.4f},
            {0f, 0.45f, -0.45f, 0.3f, 0.45f, 0.3f, -0.35f, -0.33f, 0.35f, -0.33f},
            {0f, 0.4f, -0.4f, 0.25f, 0.4f, 0.25f, 0f, -0.15f, -0.4f, -0.25f, 0.4f, -0.25f}};
    private static final float[][] FORMATION_OTHER = {{}, {0f, 0f}, {-0.35f, 0f, 0.35f, 0f},
            {0f, 0.33f, -0.33f, -0.2f, 0.33f, -0.2f}, {0f, 0.4f, -0.4f, 0f, 0.4f, 0f, 0f, -0.4f},
            {0f, 0.45f, -0.45f, 0.3f, 0.45f, 0.3f, -0.35f, -0.33f, 0.35f, -0.33f},
            {0f, 0.4f, -0.4f, 0.25f, 0.4f, 0.25f, 0f, -0.4f, -0.4f, -0.25f, 0.4f, -0.25f}};

    private static final Map<String, LazyFont> FONTS = new HashMap<>();
    private static final Set<String> MISSING_FONTS = new HashSet<>();

    /** One reusable string per text slot; rebuilt only when its text or colour changes. */
    private final Map<String, LazyFont.DrawableString> texts = new HashMap<>();
    private String weaponNameFor;
    private String weaponName;
    /** Fighter sprites for the wing icons, by wing id, with their native size. */
    private final Map<String, SpriteAPI> fighterSprites = new HashMap<>();
    private final Map<String, float[]> fighterSpriteSizes = new HashMap<>();
    /**
     * By wing id: a fleet member of the wing, whose fighter variant the game's weapon renderer reads, and the hull's
     * centre in sprite pixels, which its weapon slots are relative to (taken from a live fighter, as the spec does not
     * expose it).
     */
    private final Map<String, FleetMemberAPI> wingMembers = new HashMap<>();
    private final Map<String, float[]> fighterCenters = new HashMap<>();
    private boolean fighterWeaponsFailed;
    private SpriteAPI iconSpotlight, iconGrid;
    private boolean hologramLoaded;

    /** Schematic of {@link #schematicShip}: a copy of its hull sprite, its sprite metrics, and each cell's reveal. */
    private ShipAPI schematicShip;
    private SpriteAPI hullSprite;
    private SpriteAPI spotlight;
    private float spriteW, spriteH, spriteCX, spriteCY;
    /** Per armor cell, 0..1: cells grow in from the centre when a target is selected, as in combat. */
    private float[][] reveal;
    private long lastRevealNanos;

    // ------------------------------------------------------------------ dummy

    /**
     * Bars top right and hologram bottom right of the dummy's pane. No diamond: the engine already draws its own around
     * the host's target.
     */
    void drawTarget(ShipAPI ship, float paneX, float paneY, float paneW, float paneH, float alpha) {
        LazyFont f = font();
        if (f == null) return;

        float rowH = rowHeight(f);
        float barW = clamp(paneW * 0.4f - LABEL_W - 2f * PAD, BAR_MIN_W, BAR_MAX_W);
        float blockW = 2f * PAD + LABEL_W + barW;
        float blockH = 2f * PAD + 2f * rowH;
        float right = paneX + paneW - MARGIN, top = paneY + paneH - MARGIN;
        float left = right - blockW;
        panel(left, top - blockH, blockW, blockH, alpha);

        FluxTrackerAPI flux = ship.getFluxTracker();
        float maxFlux = Math.max(1f, flux.getMaxFlux());
        float x0 = left + PAD, rowTop = top - PAD;
        text("flux", "FLUX", ENEMY, x0, rowTop, alpha);
        bar(x0 + LABEL_W, barY(rowTop, rowH, BAR_H), barW, BAR_H, ENEMY, flux.getCurrFlux() / maxFlux,
                flux.getHardFlux() / maxFlux, alpha);
        rowTop -= rowH;
        text("hull", "HULL", ENEMY, x0, rowTop, alpha);
        bar(x0 + LABEL_W, barY(rowTop, rowH, BAR_H), barW, BAR_H, ENEMY,
                ship.getHitpoints() / Math.max(1f, ship.getMaxHitpoints()), -1f, alpha);

        float hullSize = ship.getHullSize().ordinal() / (float) HullSize.CAPITAL_SHIP.ordinal();
        float size = Math.min(SCHEMATIC_CAPITAL_SIZE * hullSize * SCHEMATIC_SCALE,
                Math.min(paneW * 0.35f, paneH - blockH - 3f * MARGIN));
        if (size > 16f) drawSchematic(ship, right, paneY + MARGIN, size, alpha);
    }

    /**
     * The game's hull schematic, step for step: the hull's silhouette is marked in the stencil buffer and everything
     * after is clipped to it; the hull sprite at a quarter alpha; every armor cell in the hostile gradient (dark when
     * stripped, orange when whole); the yellow spotlight, as bright as the hull is whole; the grid, its lines fading
     * toward their ends and toward the edges. Scaled so the sprite's longer side spans 85% of the box, and turned to the
     * ship's facing on screen, as the reticle draws it.
     */
    private void drawSchematic(ShipAPI ship, float right, float bottom, float size, float alpha) {
        if (ship != schematicShip) setUpSchematic(ship);
        ArmorGridAPI grid = ship.getArmorGrid();
        int gw = reveal.length, gh = gw > 0 ? reveal[0].length : 0;
        if (hullSprite == null || gw == 0 || gh == 0) return;
        advanceReveal();

        // Drawn about the box centre (0, 0), in the ship's frame.
        float cx = 0f, cy = 0f;
        float scale = Math.min(0.85f * size / Math.max(1f, Math.max(spriteW, spriteH)), 2f);
        float sw = spriteW * scale, sh = spriteH * scale;
        // Offset of the sprite's own centre from its middle, which sits at the box centre.
        float ix = spriteCX * scale - sw / 2f, iy = spriteCY * scale - sh / 2f;
        float cell = grid.getCellSize() * scale;
        float x0 = cx - cell * grid.getLeftOf(), y0 = cy - cell * grid.getBelow();

        GL11.glPushAttrib(GL11.GL_STENCIL_BUFFER_BIT | GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT
                | GL11.GL_CURRENT_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glTranslatef(right - size / 2f, bottom + size / 2f, 0f);
            GL11.glRotatef(ship.getFacing() - 90f, 0f, 0f, 1f);
            // Silhouette into the stencil: clear a square around the hull, then mark the sprite's opaque pixels.
            GL11.glEnable(GL11.GL_STENCIL_TEST);
            GL11.glEnable(GL11.GL_ALPHA_TEST);
            GL11.glStencilFunc(GL11.GL_NEVER, 0, 1);
            GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
            float clear = ship.getCollisionRadius() * 4f * scale;
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glColor4f(1f, 1f, 1f, 1f);
            quad(cx - clear, cy - clear, 2f * clear, 2f * clear);
            GL11.glAlphaFunc(GL11.GL_GREATER, 0f);
            GL11.glStencilFunc(GL11.GL_EQUAL, 1, 1);
            GL11.glStencilOp(GL11.GL_REPLACE, GL11.GL_REPLACE, GL11.GL_REPLACE);
            hullSprite.setAngle(0f);
            hullSprite.setColor(Color.WHITE);
            hullSprite.setNormalBlend();
            hullSprite.setCenter(spriteCX * scale, spriteCY * scale);
            hullSprite.setAlphaMult(1f);
            hullSprite.setSize(scale > 1f ? sw - 2f : sw, scale > 1f ? sh - 2f : sh);
            hullSprite.renderAtCenter(cx, cy);
            GL11.glStencilOp(GL11.GL_KEEP, GL11.GL_KEEP, GL11.GL_KEEP);
            GL11.glDisable(GL11.GL_ALPHA_TEST);

            hullSprite.setSize(sw, sh);
            hullSprite.setAlphaMult(0.25f * alpha);
            hullSprite.renderAtCenter(cx, cy);

            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
            for (int x = 0; x < gw; x++) {
                for (int y = 0; y < gh; y++) {
                    float grow = Math.max(0f, reveal[x][y]);
                    float side = cell * grow;
                    float f = clamp(grid.getArmorFraction(x, y), 0f, 1f);
                    GL11.glColor4f((int) (f * 230f + 25f) / 255f, (int) (f * 75f + 25f) / 255f, 25f / 255f,
                            (int) (CELL_ALPHA * alpha * grow) / 255f);
                    quad(x0 + x * cell + (cell - side) / 2f, y0 + y * cell + (cell - side) / 2f, side, side);
                }
            }

            if (spotlight != null) {
                spotlight.setSize(size, size);
                spotlight.setAlphaMult(alpha * clamp(ship.getHitpoints() / Math.max(1f, ship.getMaxHitpoints()), 0f, 1f));
                spotlight.renderAtCenter(cx - ix, cy - iy);
            }

            // Sprite.render leaves blending off.
            GL11.glDisable(GL11.GL_TEXTURE_2D);
            GL11.glEnable(GL11.GL_BLEND);
            GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            float gridW = cell * gw, gridH = cell * gh;
            for (int i = 0; i <= gh; i++) {
                fadingLine(x0, y0 + i * cell, x0 + gridW, y0 + i * cell, gridLineAlpha(i, gh, alpha));
            }
            for (int i = 0; i <= gw; i++) {
                fadingLine(x0 + i * cell, y0, x0 + i * cell, y0 + gridH, gridLineAlpha(i, gw, alpha));
            }
        } finally {
            GL11.glPopMatrix();
            GL11.glPopAttrib();
        }
    }

    /** Brightest at the grid's middle line, none at its edges. */
    private static float gridLineAlpha(int line, int lines, float alpha) {
        float half = lines / 2f;
        float a = (1f - Math.abs(line - half) / half) * 0.75f;
        return a * a * alpha * 0.5f;
    }

    /** A one-unit line in the grid colour, transparent at both ends and {@code alpha} at its middle. */
    private static void fadingLine(float x1, float y1, float x2, float y2, float alpha) {
        boolean vertical = x1 == x2;
        float dx = vertical ? 0.5f : 0f, dy = vertical ? 0f : 0.5f;
        float mx = (x1 + x2) / 2f, my = (y1 + y2) / 2f;
        float r = GRID_COLOR.getRed() / 255f, g = GRID_COLOR.getGreen() / 255f, b = GRID_COLOR.getBlue() / 255f;
        GL11.glBegin(GL11.GL_QUAD_STRIP);
        GL11.glColor4f(r, g, b, 0f);
        GL11.glVertex2f(x1 - dx, y1 - dy);
        GL11.glVertex2f(x1 + dx, y1 + dy);
        GL11.glColor4f(r, g, b, alpha);
        GL11.glVertex2f(mx - dx, my - dy);
        GL11.glVertex2f(mx + dx, my + dy);
        GL11.glColor4f(r, g, b, 0f);
        GL11.glVertex2f(x2 - dx, y2 - dy);
        GL11.glVertex2f(x2 + dx, y2 + dy);
        GL11.glEnd();
    }

    /**
     * New target: a copy of its hull sprite (the live one draws the ship in the scene), its metrics, and the reveal
     * the game starts each cell at, further from the grid's centre starting further below zero.
     */
    private void setUpSchematic(ShipAPI ship) {
        schematicShip = ship;
        float[][] cells = ship.getArmorGrid().getGrid();
        int gw = cells.length, gh = gw > 0 ? cells[0].length : 0;
        reveal = new float[gw][gh];
        float halfDiagonal = Math.max(1e-3f, (float) Math.sqrt(gw * gw / 4f + gh * gh / 4f));
        float spread = gw * gh * 0.03f;
        for (int x = 0; x < gw; x++) {
            for (int y = 0; y < gh; y++) {
                float dx = Math.abs(x + 0.5f - gw / 2f), dy = Math.abs(y + 0.5f - gh / 2f);
                reveal[x][y] = -spread * (float) Math.sqrt(dx * dx + dy * dy) / halfDiagonal;
            }
        }
        lastRevealNanos = 0L;

        hullSprite = null;
        try {
            SpriteAPI live = ship.getSpriteAPI();
            spriteW = live.getWidth();
            spriteH = live.getHeight();
            spriteCX = live.getCenterX() >= 0f ? live.getCenterX() : spriteW / 2f;
            spriteCY = live.getCenterY() >= 0f ? live.getCenterY() : spriteH / 2f;
            hullSprite = Global.getSettings().getSprite(ship.getHullSpec().getSpriteName());
        } catch (RuntimeException e) {
            Log.debug("no schematic sprite for %s: %s", ship.getHullSpec().getHullId(), e);
        }
        if (spotlight == null) {
            try {
                Global.getSettings().loadTexture(SPOTLIGHT_PATH);
                spotlight = Global.getSettings().getSprite(SPOTLIGHT_PATH);
                spotlight.setColor(SPOTLIGHT_COLOR);
                spotlight.setBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE);
            } catch (IOException | RuntimeException e) {
                Log.debug("no schematic spotlight: %s", e);
            }
        }
    }

    /** The game's reveal rate: five units per second, capped at fully shown. */
    private void advanceReveal() {
        long now = System.nanoTime();
        float dt = lastRevealNanos == 0L ? 0f : Math.min(0.1f, (now - lastRevealNanos) / 1e9f);
        lastRevealNanos = now;
        if (dt <= 0f) return;
        for (float[] column : reveal) {
            for (int y = 0; y < column.length; y++) column[y] = Math.min(1f, column[y] + dt * 5f);
        }
    }

    // ------------------------------------------------------------------ weapon

    /** The weapon's line from the combat weapon list, in the bottom left corner of the host's pane. */
    void drawWeapon(WeaponAPI weapon, float paneX, float paneY, float paneW, float paneH, float alpha) {
        LazyFont f = font();
        if (f == null) return;
        boolean ammo = weapon.usesAmmo();
        boolean reload = ammo || weapon.getCooldown() > 0f;
        float tail = (ammo ? GAP + AMMO_W : 0f) + (reload ? GAP + RELOAD_W : 0f);
        String name = weaponName(f, weapon, Math.max(30f, paneW * 0.55f - 2f * PAD - tail));
        float nameW = f.calcWidth(name, f.getBaseHeight());

        float rowH = rowHeight(f);
        float blockW = 2f * PAD + nameW + tail;
        float blockH = 2f * PAD + rowH;
        float left = paneX + MARGIN, bottom = paneY + MARGIN;
        float rowTop = bottom + blockH - PAD;
        panel(left, bottom, blockW, blockH, alpha);

        float x = left + PAD;
        text("weapon", name, FRIEND, x, rowTop, alpha);
        x += nameW;
        if (ammo) {
            LazyFont.DrawableString count = string("ammo", String.valueOf(weapon.getAmmo()), FRIEND, alpha);
            if (count != null) drawText(count, x + GAP + AMMO_W - count.getWidth(), rowTop);
            x += GAP + AMMO_W;
        }
        if (reload) {
            bar(x + GAP, barY(rowTop, rowH, RELOAD_H), RELOAD_W, RELOAD_H, FRIEND, reloadFraction(weapon), -1f, alpha);
        }
    }

    /** Upper-case display name, cut to fit (measured once per weapon). */
    private String weaponName(LazyFont f, WeaponAPI weapon, float maxW) {
        String id = weapon.getId() + "@" + Math.round(maxW);
        if (!id.equals(weaponNameFor)) {
            String name = weapon.getDisplayName().toUpperCase(Locale.ROOT);
            while (name.length() > 4 && f.calcWidth(name, f.getBaseHeight()) > maxW) {
                name = name.substring(0, name.length() - 2) + ".";
            }
            weaponName = name;
            weaponNameFor = id;
        }
        return weaponName;
    }

    /**
     * As the combat HUD fills it: progress toward the next reload while ammo regenerates, otherwise the cooldown
     * between shots (full when ready to fire, empty once a non-regenerating magazine is spent).
     */
    private static float reloadFraction(WeaponAPI weapon) {
        if (weapon.usesAmmo()) {
            boolean regenerates = weapon.getAmmoTracker().getAmmoPerSecond() > 0f;
            if (regenerates && weapon.getAmmo() < weapon.getMaxAmmo()) {
                return clamp(weapon.getAmmoTracker().getReloadProgress(), 0f, 1f);
            }
            if (weapon.getAmmo() <= 0) return 0f;
        }
        float cooldown = weapon.getCooldown();
        return cooldown > 0f ? clamp(1f - weapon.getCooldownRemaining() / cooldown, 0f, 1f) : 1f;
    }

    // ------------------------------------------------------------------ wings

    /**
     * The carrier's wings as the combat fighter widget shows them, in the bottom left corner of its pane: one icon per
     * wing with its fighters in formation, under each a bar filling toward the next replacement (the game's widget has
     * none), and the replacement rate to their right, in the HUD's smaller font. The widget's orders line is left out:
     * the preview always engages.
     */
    void drawWings(ShipAPI carrier, float paneX, float paneY, float paneW, float paneH, float alpha) {
        LazyFont f = font();
        if (f == null) return;
        List<FighterLaunchBayAPI> bays = new ArrayList<>();
        for (FighterLaunchBayAPI bay : carrier.getLaunchBaysCopy()) {
            if (bay.getWing() != null) bays.add(bay);
        }
        if (bays.isEmpty()) return;

        String rate = "replacement rate: " + Math.round(100f * carrier.getSharedFighterReplacementRate()) + "%";
        float iconsW = bays.size() * WING_ICON + (bays.size() - 1) * GAP;
        float blockW = 2f * PAD + iconsW + GAP + f.calcWidth(rate, f.getBaseHeight());
        float blockH = 2f * PAD + WING_ICON + PAD + COOLDOWN_H;
        float left = paneX + MARGIN, bottom = paneY + MARGIN;
        panel(left, bottom, blockW, blockH, alpha);

        float iconCy = bottom + blockH - PAD - WING_ICON / 2f;
        for (int i = 0; i < bays.size(); i++) {
            float x = left + PAD + i * (WING_ICON + GAP);
            drawWingIcon(bays.get(i).getWing(), x + WING_ICON / 2f, iconCy, alpha);
            bar(x, bottom + PAD, WING_ICON, COOLDOWN_H, FRIEND, replacementProgress(bays.get(i)), -1f, alpha);
        }
        text("wings.rate", rate, FRIEND, left + PAD + iconsW + GAP, iconCy + f.getBaseHeight() / 2f, alpha);
    }

    /**
     * The wing in formation as the game's icon draws it, fighter by fighter: the tinted hull, its weapons, the
     * hologram. Sized as the game sizes them: the more fighters the wing has, the smaller each.
     */
    private void drawWingIcon(FighterWingAPI wing, float cx, float cy, float alpha) {
        FighterWingSpecAPI spec = wing.getSpec();
        SpriteAPI sprite = fighterSprite(spec);
        if (sprite == null) return;
        float[] size = fighterSpriteSizes.get(spec.getId());
        float box = WING_ICON + ICON_OVERHANG;
        float h = 0.375f * box * (1f - spec.getNumFighters() / 15f);
        if (h <= 0f) return;
        float w = h * size[0] / Math.max(1f, size[1]);
        float[][] formation = spec.getFormation() == FormationType.BOX ? FORMATION_BOX
                : spec.getFormation() == FormationType.CLAW ? FORMATION_CLAW
                : spec.getFormation() == FormationType.V ? FORMATION_V : FORMATION_OTHER;
        int shown = Math.min(spec.getNumFighters(), formation.length - 1);
        float[] slots = formation[shown];
        List<ShipAPI> members = wing.getWingMembers();
        int alive = members.size();
        if (alive > 0 && !fighterCenters.containsKey(spec.getId())) {
            SpriteAPI live = members.get(0).getSpriteAPI();
            fighterCenters.put(spec.getId(), new float[]{live.getCenterX() >= 0f ? live.getCenterX() : size[0] / 2f,
                    live.getCenterY() >= 0f ? live.getCenterY() : size[1] / 2f});
        }
        loadHologram();

        sprite.setSize(w, h);
        sprite.setAngle(0f);
        sprite.setColor(FRIEND);
        for (int i = 0; i < shown; i++) {
            boolean up = i < alive;
            float x = cx + slots[2 * i] * box / 2f, y = cy + slots[2 * i + 1] * box / 2f;
            sprite.setNormalBlend();
            sprite.setAlphaMult((up ? FIGHTER_ALIVE_ALPHA : FIGHTER_LOST_ALPHA) * alpha);
            sprite.renderAtCenter(x, y);
            drawFighterWeapons(spec, x - w / 2f, y - h / 2f, w / Math.max(1f, size[0]), alpha);
            drawHologram(sprite, x, y, (up ? 1f : HOLOGRAM_LOST_ALPHA) * alpha, up);
        }
        // Sprite.render leaves texturing on and blending off.
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
    }

    /**
     * The game's weapon renderer over one fighter, hull facing up: slots placed from the hull's centre, sprites in
     * their own colours at full alpha, whether the fighter is up or lost (as in combat).
     */
    private void drawFighterWeapons(FighterWingSpecAPI spec, float spriteLeft, float spriteBottom, float scale,
                                    float alpha) {
        if (ObfBridge.renderMemberWeapons == null || fighterWeaponsFailed) return;
        float[] center = fighterCenters.get(spec.getId());
        FleetMemberAPI member = wingMember(spec);
        if (center == null || member == null) return;
        try {
            ObfBridge.renderMemberWeapons.invoke(null, member, spriteLeft + center[0] * scale,
                    spriteBottom + center[1] * scale, 90f, scale, alpha, false);
        } catch (RuntimeException e) {
            fighterWeaponsFailed = true;
            Log.debug("fighter weapons not drawn: %s", e);
        }
    }

    /**
     * The game's hologram over one fighter, through the framebuffer's alpha as the game does it: alpha cleared, the
     * hull's alpha written squared and then multiplied by the spotlight; through that mask, the spotlight added in the
     * widget colour (fighters up only), then the grid. The game clips all this to the sprite with the stencil; clearing
     * the mask over everything the overlay covers, and making it opaque again after, comes to the same.
     */
    private void drawHologram(SpriteAPI hull, float x, float y, float a, boolean lit) {
        if (iconSpotlight == null || iconGrid == null) return;
        float w = hull.getWidth(), h = hull.getHeight();
        float reach = Math.max(1.25f * Math.max(w, h), Math.max(iconGrid.getWidth(), iconGrid.getHeight())) / 2f + 1f;

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(false, false, false, true);
        GL11.glColor4f(0f, 0f, 0f, 0f);
        quad(x - reach, y - reach, 2f * reach, 2f * reach);
        hull.setAlphaMult(a);
        hull.setBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ZERO);
        hull.renderAtCenter(x, y);
        iconSpotlight.setSize(1.25f * w, 1.25f * h);
        iconSpotlight.setAlphaMult(a);
        iconSpotlight.setBlendFunc(GL11.GL_ZERO, GL11.GL_SRC_ALPHA);
        iconSpotlight.renderAtCenter(x, y);
        GL11.glColorMask(true, true, true, true);
        hull.setNormalBlend();

        if (lit) {
            iconSpotlight.setBlendFunc(GL11.GL_DST_ALPHA, GL11.GL_ONE);
            iconSpotlight.renderAtCenter(x, y);
        }
        iconGrid.renderAtCenter(x - 0.5f, (int) y);

        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColorMask(false, false, false, true);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        quad(x - reach, y - reach, 2f * reach, 2f * reach);
        GL11.glColorMask(true, true, true, true);
    }

    private void loadHologram() {
        if (hologramLoaded) return;
        hologramLoaded = true;
        try {
            Global.getSettings().loadTexture(ICON_SPOTLIGHT_PATH);
            Global.getSettings().loadTexture(ICON_GRID_PATH);
            iconSpotlight = Global.getSettings().getSprite(ICON_SPOTLIGHT_PATH);
            iconSpotlight.setColor(FRIEND);
            iconGrid = Global.getSettings().getSprite(ICON_GRID_PATH);
            iconGrid.setColor(GRID_COLOR);
            iconGrid.setBlendFunc(GL11.GL_DST_ALPHA, GL11.GL_ONE);
        } catch (IOException | RuntimeException e) {
            iconSpotlight = null;
            iconGrid = null;
            Log.debug("no fighter hologram: %s", e);
        }
    }

    private FleetMemberAPI wingMember(FighterWingSpecAPI spec) {
        if (!wingMembers.containsKey(spec.getId())) {
            FleetMemberAPI member = null;
            try {
                member = Global.getFactory().createFleetMember(FleetMemberType.FIGHTER_WING, spec.getId());
            } catch (RuntimeException e) {
                Log.debug("no fleet member for wing %s: %s", spec.getId(), e);
            }
            wingMembers.put(spec.getId(), member);
        }
        return wingMembers.get(spec.getId());
    }

    private SpriteAPI fighterSprite(FighterWingSpecAPI spec) {
        if (!fighterSprites.containsKey(spec.getId())) {
            SpriteAPI sprite = null;
            try {
                sprite = Global.getSettings().getSprite(spec.getVariant().getHullSpec().getSpriteName());
                fighterSpriteSizes.put(spec.getId(), new float[]{sprite.getWidth(), sprite.getHeight()});
            } catch (RuntimeException e) {
                Log.debug("no icon sprite for wing %s: %s", spec.getId(), e);
                sprite = null;
            }
            fighterSprites.put(spec.getId(), sprite);
        }
        return fighterSprites.get(spec.getId());
    }

    /** Full while the wing is whole; otherwise the share of the current replacement interval already elapsed. */
    private static float replacementProgress(FighterLaunchBayAPI bay) {
        FighterWingAPI wing = bay.getWing();
        if (wing.getWingMembers().size() >= wing.getSpec().getNumFighters()) return 1f;
        float interval = bay.getCurrReplacementIntervalDuration();
        return interval > 0f ? clamp(1f - bay.getTimeUntilNextReplacement() / interval, 0f, 1f) : 0f;
    }

    // ------------------------------------------------------------------ drawing

    /**
     * A bar as the combat HUD draws it: a solid fill and, until full, a thin tick at its end. {@code hard} >= 0 splits
     * the fill as the flux bar does: hard flux solid, the soft flux beyond it dimmer.
     */
    private static void bar(float x, float y, float w, float h, Color c, float fill, float hard, float alpha) {
        float f = clamp(fill, 0f, 1f);
        if (hard >= 0f) {
            float hf = Math.min(f, clamp(hard, 0f, 1f));
            color(c, alpha);
            quad(x, y, w * hf, h);
            color(c, 0.55f * alpha);
            quad(x + w * hf, y, w * (f - hf), h);
        } else {
            color(c, alpha);
            quad(x, y, w * f, h);
        }
        if (f < 0.995f) {
            color(c, alpha);
            quad(x + w - 1f, y - 1f, 1f, h + 2f);
        }
    }

    /** Dark backing so the text reads over ships, shots and explosions. */
    private static void panel(float x, float y, float w, float h, float alpha) {
        GL11.glColor4f(0f, 0f, 0f, PANEL_ALPHA * alpha);
        quad(x, y, w, h);
    }

    private void text(String slot, String text, Color color, float x, float top, float alpha) {
        LazyFont.DrawableString s = string(slot, text, color, alpha);
        if (s != null) drawText(s, x, top);
    }

    private LazyFont.DrawableString string(String slot, String text, Color color, float alpha) {
        return string(font(), slot, text, color, alpha);
    }

    private LazyFont.DrawableString string(LazyFont f, String slot, String text, Color color, float alpha) {
        if (f == null) return null;
        LazyFont.DrawableString s = texts.get(slot);
        if (s == null) {
            s = f.createText(text, color, f.getBaseHeight());
            s.setAnchor(LazyFont.TextAnchor.TOP_LEFT);
            texts.put(slot, s);
        } else if (!text.equals(s.getText())) {
            s.setText(text);
        }
        int a = Math.round(255f * clamp(alpha, 0f, 1f));
        Color current = s.getBaseColor();
        if (current == null || current.getAlpha() != a || (current.getRGB() & 0xFFFFFF) != (color.getRGB() & 0xFFFFFF)) {
            s.setBaseColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), a));
        }
        return s;
    }

    /** Whole UI units: the bitmap font blurs at fractional positions. */
    private static void drawText(LazyFont.DrawableString s, float x, float top) {
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        s.draw(Math.round(x), Math.round(top));
        GL11.glDisable(GL11.GL_TEXTURE_2D);
    }

    void dispose() {
        for (LazyFont.DrawableString s : texts.values()) s.dispose();
        texts.clear();
        schematicShip = null;
        hullSprite = null;
        fighterSprites.clear();
        fighterSpriteSizes.clear();
        wingMembers.clear();
        fighterCenters.clear();
        iconSpotlight = null;
        iconGrid = null;
        hologramLoaded = false;
    }

    private static LazyFont font() {
        return font(FONT_PATH);
    }

    private static LazyFont font(String path) {
        LazyFont f = FONTS.get(path);
        if (f == null && !MISSING_FONTS.contains(path)) {
            try {
                f = LazyFont.loadFont(path);
                FONTS.put(path, f);
            } catch (FontException | RuntimeException e) {
                MISSING_FONTS.add(path);
                Log.error("font " + path + " unavailable; previews show no readouts in it", e);
            }
        }
        return f;
    }

    private static Color settingsColor(String key, Color fallback) {
        try {
            Color c = Global.getSettings().getColor(key);
            return c != null ? c : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static float rowHeight(LazyFont f) {
        return f.getBaseHeight() + 3f;
    }

    /** Bars sit on the text's vertical middle. */
    private static float barY(float rowTop, float rowH, float barH) {
        return rowTop - (rowH - 3f) / 2f - barH / 2f;
    }

    private static void color(Color c, float alpha) {
        GL11.glColor4f(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, alpha);
    }

    private static void quad(float x, float y, float w, float h) {
        GL11.glBegin(GL11.GL_QUADS);
        GL11.glVertex2f(x, y);
        GL11.glVertex2f(x + w, y);
        GL11.glVertex2f(x + w, y + h);
        GL11.glVertex2f(x, y + h);
        GL11.glEnd();
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }
}
