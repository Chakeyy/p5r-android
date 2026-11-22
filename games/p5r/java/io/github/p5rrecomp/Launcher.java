package io.github.p5rrecomp;

import android.app.Activity;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.system.Os;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Persona 5 Royal Android Launcher — Reworked Landscape Command Deck (Direction A: P5 Pause / Calling Card).
 * Preserves 100% compatibility with config.txt, SaveFiles, ImportActivity, ControlsEditor, Lang, and NativeActivity.
 */
public class Launcher extends Activity {
    static final String DATA = "Persona 5 Royal";

    static final int TAB_PLAY = 0;
    static final int TAB_DISPLAY = 1;
    static final int TAB_PERFORMANCE = 2;
    static final int TAB_CONTROLS = 3;
    static final int TAB_FILES = 4;
    static final int TAB_COUNT = 5;

    static final int PICK_ZIP = 1, PICK_FOLDER = 2, PICK_GAME = 3, PICK_KEYS = 4,
            PICK_ICON = 5, PREPARE = 6, PICK_CPK = 7, PICK_DRIVER = 8, PICK_WALLPAPER = 9;

    static final class Option {
        final int tab;
        final String key, es, en;
        final String descEs, descEn;
        final String[] values, labelsEs, labelsEn;
        final boolean modal;
        int pick;
        View rowView;
        P5Views.SegmentedControl segView;
        P5Views.SlashButton modalBtn;

        Option(int tab, String key, String es, String en, String descEs, String descEn,
               String[] values, String[] labelsEs, String[] labelsEn, int pick, boolean modal) {
            this.tab = tab;
            this.key = key;
            this.es = es;
            this.en = en;
            this.descEs = descEs;
            this.descEn = descEn;
            this.values = values;
            this.labelsEs = labelsEs;
            this.labelsEn = labelsEn;
            this.pick = pick;
            this.modal = modal;
        }
    }

    final List<Option> options = new ArrayList<>();
    final Map<String, String> config = new LinkedHashMap<>();

    private FrameLayout rootFrame;
    private P5BackgroundView bgView;
    private LinearLayout foregroundRoot;
    private final P5Views.NavTabView[] navTabs = new P5Views.NavTabView[TAB_COUNT];
    private final View[] tabPanels = new View[TAB_COUNT];
    private final LinearLayout[] tabLists = new LinearLayout[TAB_COUNT];
    private final ScrollView[] tabScrolls = new ScrollView[TAB_COUNT];
    private final P5Views.SubTabBar[] subTabBars = new P5Views.SubTabBar[TAB_COUNT];
    private final int[] activeSubTab = new int[TAB_COUNT];
    private final int[] subTabCount = new int[TAB_COUNT];
    private final Map<View, Integer> rowSubTabs = new java.util.HashMap<>();
    private int activeTab = TAB_PLAY;

    // Physical controller navigation state
    private P5Views.ControllerHintBar controllerHintBar;
    private boolean controllerMode = false;
    private int focusedRowIdx = 0;
    private int focusedBtnIdx = 0;
    private int stickDirY = 0;
    private int stickDirX = 0;
    private boolean ltDown = false;
    private boolean rtDown = false;
    private long lastTriggerMs = 0L;
    private final Handler stickRepeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable stickRepeatTask = new Runnable() {
        @Override
        public void run() {
            if (stickDirY != 0) {
                onControllerVertical(stickDirY);
                stickRepeatHandler.postDelayed(this, 125L);
            } else if (stickDirX != 0) {
                onControllerHorizontal(stickDirX);
                stickRepeatHandler.postDelayed(this, 145L);
            }
        }
    };

    // Hybrid PLAY buttons: heroPlay shown on TAB_PLAY, pinnedPlay shown on Settings tabs
    private P5Views.SlashButton heroPlay;
    private P5Views.SlashButton pinnedPlay;
    private TextView railStatusText;
    private TextView heroStatusTitle;
    private TextView heroStatusBody;
    private P5Views.AngledRowLayout heroStatusCard;

    // Live spec badges on Home screen
    private P5Views.SpecBadge specRes;
    private P5Views.SpecBadge specView;
    private P5Views.SpecBadge specFps;
    private P5Views.SpecBadge specGfx;
    private P5Views.SpecBadge specExtra;

    // Live file-status subtitles on Files tab
    private TextView gameSub;
    private TextView keysSub;
    private TextView driverSub;
    private TextView wpSub;
    private View wpDimRow;
    private P5Views.SegmentedControl wpDimSeg;
    private View wpMotionRow;
    private P5Views.SegmentedControl wpMotionSeg;
    private View wpHalftoneRow;
    private P5Views.SegmentedControl wpHalftoneSeg;
    private P5Views.SegmentedControl sfxSeg;
    private P5Views.SegmentedControl hapticsSeg;

    private Bitmap currentWallpaperBmp;
    private boolean hasPausedOnce = false;
    private boolean launchingGame = false;
    private P5Views.IntroOverlayView introOverlay;
    private P5Views.LaunchSlashOverlayView launchOverlay;

    String t(String spanish, String english) { return Lang.t(spanish, english); }

    String label(Option o) { return t(o.labelsEs[o.pick], o.labelsEn[o.pick]); }

    String[] labelsOf(Option o) {
        String[] out = new String[o.values.length];
        for (int i = 0; i < o.values.length; i++) out[i] = t(o.labelsEs[i], o.labelsEn[i]);
        return out;
    }

    Option findOption(String key) {
        for (Option o : options) {
            if (key.equals(o.key)) return o;
        }
        return null;
    }

    static File dataDir() { return new File(Environment.getExternalStorageDirectory(), DATA); }

    static final class Api30Helper {
        static boolean isExternalStorageManager() {
            return Environment.isExternalStorageManager();
        }
    }

    static final class Api31Helper {
        static void hookSplashExit(Activity activity, final Runnable onReady) {
            try {
                activity.getSplashScreen().setOnExitAnimationListener(
                        new android.window.SplashScreen.OnExitAnimationListener() {
                            @Override
                            public void onSplashScreenExit(android.window.SplashScreenView splashView) {
                                splashView.remove();
                                onReady.run();
                            }
                        });
            } catch (Throwable ignored) {
            }
        }
    }

    boolean hasAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
        }
        return Api30Helper.isExternalStorageManager();
    }

    // ---- game file & keys ----

    SharedPreferences prefs() { return getSharedPreferences("game", MODE_PRIVATE); }

    String gameUri() { return prefs().getString("uri", null); }

    String gameName() { return prefs().getString("name", ""); }

    static File keysFile(Context c, String name) { return new File(new File(c.getFilesDir(), "keys"), name); }

    boolean hasKeys() { return keysFile(this, "prod.keys").length() > 0; }

    boolean gameReadable() {
        try (ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r")) {
            return f != null;
        } catch (Exception e) {
            return false;
        }
    }

    boolean prepared() {
        File note = new File(dataDir(), "spirv/import.txt");
        if (!note.canRead()) return false;
        try (BufferedReader r = new BufferedReader(new FileReader(note))) {
            return gameName().equals(r.readLine());
        } catch (Exception e) {
            return false;
        }
    }

    String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "" : last.substring(last.lastIndexOf('/') + 1);
    }

    void pick(int request, String type, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(type);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, request);
    }

    void onGamePicked(Uri uri) {
        String name = displayName(uri);
        String lower = name.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".nsp") && !lower.endsWith(".xci")) {
            tell(t("Archivo no válido", "Not a game file"),
                    t("Elige el archivo .nsp o .xci del juego base (no el de la actualización).",
                            "Choose the base game's .nsp or .xci file (not the update's)."));
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) {
        }
        prefs().edit().putString("uri", uri.toString()).putString("name", name).apply();
    }

    void onKeysPicked(Intent data) {
        List<Uri> uris = new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int k = 0; k < clip.getItemCount(); k++) uris.add(clip.getItemAt(k).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        boolean prod = false;
        for (Uri uri : uris) {
            String name = displayName(uri).toLowerCase(Locale.ROOT);
            String target = name.contains("title") ? "title.keys" : "prod.keys";
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                byte[] buf = new byte[1 << 20];
                int n = 0, r;
                while (in != null && n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
                String text = new String(buf, 0, n, "UTF-8");
                if (target.equals("prod.keys") && !text.contains("header_key") && !text.contains("master_key_00")) continue;
                File f = keysFile(this, target);
                f.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(buf, 0, n);
                }
                prod |= target.equals("prod.keys");
            } catch (Exception ignored) {
            }
        }
        if (!prod && !hasKeys()) {
            tell(t("Claves no válidas", "Not a keys file"),
                    t("Ese archivo no tiene las claves (header_key). Elige el prod.keys de tu consola.",
                            "That file has no keys (header_key). Choose your console's prod.keys."));
        }
    }

    void prepare() {
        Intent i = new Intent(this, ImportActivity.class);
        i.putExtra(ImportActivity.EXTRA_URI, gameUri());
        i.putExtra(ImportActivity.EXTRA_NAME, gameName());
        startActivityForResult(i, PREPARE);
    }

    boolean openGameForRuntime() {
        try {
            ParcelFileDescriptor f = getContentResolver().openFileDescriptor(Uri.parse(gameUri()), "r");
            if (f == null) return false;
            Os.setenv("NX_GAME_FD", String.valueOf(f.detachFd()), true);
            Os.setenv("NX_GAME_NAME", gameName(), true);
            Os.setenv("NX_KEYS", keysFile(this, "prod.keys").getPath(), true);
            File tk = keysFile(this, "title.keys");
            if (tk.exists()) Os.setenv("NX_TITLE_KEYS", tk.getPath(), true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void onIconPicked(Uri uri) {
        try {
            Bitmap src;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                src = BitmapFactory.decodeStream(in);
            }
            if (src == null) throw new IllegalArgumentException("not a picture");
            int side = Math.min(src.getWidth(), src.getHeight());
            Bitmap square = Bitmap.createBitmap(src, (src.getWidth() - side) / 2, (src.getHeight() - side) / 2, side, side);
            Bitmap icon = Bitmap.createScaledBitmap(square, 432, 432, true);
            ShortcutManager sm = getSystemService(ShortcutManager.class);
            if (sm == null || !sm.isRequestPinShortcutSupported()) {
                tell(t("Icono", "Icon"), t("Tu pantalla de inicio no admite accesos directos.",
                        "Your home screen does not support shortcuts."));
                return;
            }
            Intent launch = new Intent(this, Launcher.class).setAction(Intent.ACTION_MAIN);
            ShortcutInfo info = new ShortcutInfo.Builder(this, "picture_" + System.currentTimeMillis())
                    .setShortLabel("P5R").setLongLabel("Persona 5 Royal").setIcon(Icon.createWithAdaptiveBitmap(icon))
                    .setIntent(launch).build();
            sm.requestPinShortcut(info, null);
            tell(t("Icono", "Icon"), t("Acepta el aviso para poner el acceso directo con tu imagen en la pantalla de inicio.",
                    "Accept the prompt to pin the shortcut with your picture to your home screen."));
        } catch (Exception e) {
            tell(t("Icono", "Icon"), t("No se pudo usar esa imagen.", "That picture could not be used."));
        }
    }

    // ---- custom launcher wallpaper ----

    File wallpaperFile() { return new File(getFilesDir(), "launcher_wallpaper.jpg"); }

    boolean hasCustomWallpaper() { return wallpaperFile().length() > 0; }

    int wallpaperDim() { return Math.max(0, Math.min(2, prefs().getInt("wp_dim", 1))); }

    void setWallpaperDim(int level) {
        int clamped = Math.max(0, Math.min(2, level));
        prefs().edit().putInt("wp_dim", clamped).apply();
        if (bgView != null) bgView.setWallpaperDim(clamped);
    }

    int halftoneMode() {
        return Math.max(P5BackgroundView.HALFTONE_RIBBONS,
                Math.min(P5BackgroundView.HALFTONE_OFF, prefs().getInt("wp_halftone", P5BackgroundView.HALFTONE_RIBBONS)));
    }

    void setHalftoneMode(int mode) {
        int clamped = Math.max(P5BackgroundView.HALFTONE_RIBBONS,
                Math.min(P5BackgroundView.HALFTONE_OFF, mode));
        prefs().edit().putInt("wp_halftone", clamped).apply();
        if (bgView != null) bgView.setHalftoneMode(clamped);
    }

    boolean animatedDefaultWallpaper() {
        return prefs().getBoolean("wp_animated", true);
    }

    void setAnimatedDefaultWallpaper(boolean animated) {
        prefs().edit().putBoolean("wp_animated", animated).apply();
        if (bgView != null) bgView.setAnimatedDefault(animated);
    }

    boolean launcherSfxEnabled() {
        return prefs().getBoolean("launcher_sfx", true);
    }

    void setLauncherSfxEnabled(boolean enabled) {
        prefs().edit().putBoolean("launcher_sfx", enabled).apply();
        P5Feedback.setSfxEnabled(enabled);
    }

    boolean launcherHapticsEnabled() {
        return prefs().getBoolean("launcher_haptics", true);
    }

    void setLauncherHapticsEnabled(boolean enabled) {
        prefs().edit().putBoolean("launcher_haptics", enabled).apply();
        P5Feedback.setHapticsEnabled(enabled);
    }

    void loadWallpaper() {
        if (bgView != null) {
            bgView.setWallpaperDim(wallpaperDim());
            bgView.setHalftoneMode(halftoneMode());
            bgView.setAnimatedDefault(animatedDefaultWallpaper());
        }
        File f = wallpaperFile();
        if (!f.canRead()) {
            if (currentWallpaperBmp != null) {
                currentWallpaperBmp.recycle();
                currentWallpaperBmp = null;
            }
            if (bgView != null) bgView.setWallpaper(null);
            return;
        }
        try {
            Bitmap bmp = BitmapFactory.decodeFile(f.getPath());
            if (currentWallpaperBmp != null && currentWallpaperBmp != bmp) currentWallpaperBmp.recycle();
            currentWallpaperBmp = bmp;
            if (bgView != null) bgView.setWallpaper(bmp);
        } catch (Exception ignored) {
        }
    }

    void onWallpaperPicked(Uri uri) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IllegalArgumentException("unreadable image");
            }
            int sample = 1;
            while ((bounds.outWidth / sample) > 2048 || (bounds.outHeight / sample) > 2048) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap decoded;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                decoded = BitmapFactory.decodeStream(in, null, opts);
            }
            if (decoded == null) throw new IllegalArgumentException("decode failed");
            File dest = wallpaperFile();
            try (OutputStream out = new FileOutputStream(dest)) {
                decoded.compress(Bitmap.CompressFormat.JPEG, 92, out);
            }
            decoded.recycle();
            loadWallpaper();
            updateWallpaperUi();
            Toast.makeText(this, t("Fondo aplicado", "Wallpaper applied"), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            tell(t("Fondo de pantalla", "Wallpaper"),
                    t("No se pudo cargar esa imagen.", "Could not load that image."));
        }
    }

    void resetLauncherWallpaper() {
        wallpaperFile().delete();
        loadWallpaper();
        updateWallpaperUi();
        Toast.makeText(this, t("Fondo por defecto restaurado", "Default wallpaper restored"), Toast.LENGTH_SHORT).show();
    }

    void updateWallpaperUi() {
        boolean custom = hasCustomWallpaper();
        if (wpSub != null) {
            wpSub.setText(custom
                    ? t("Imagen personalizada activa", "Custom image active")
                    : t("Fondo por defecto", "Default Wallpaper"));
        }
        if (wpDimRow != null) {
            float alpha = custom ? 1.0f : 0.36f;
            wpDimRow.setTag(alpha);
            wpDimRow.setAlpha(alpha);
        }
        if (wpDimSeg != null) {
            wpDimSeg.setEnabled(custom);
            wpDimSeg.setSelectedIndex(wallpaperDim());
        }
        if (wpMotionRow != null) {
            float alpha = custom ? 0.36f : 1.0f;
            wpMotionRow.setTag(alpha);
            wpMotionRow.setAlpha(alpha);
        }
        if (wpMotionSeg != null) {
            wpMotionSeg.setEnabled(!custom);
            wpMotionSeg.setSelectedIndex(animatedDefaultWallpaper() ? 1 : 0);
        }
        if (wpHalftoneRow != null) {
            float alpha = custom ? 0.36f : 1.0f;
            wpHalftoneRow.setTag(alpha);
            wpHalftoneRow.setAlpha(alpha);
        }
        if (wpHalftoneSeg != null) {
            wpHalftoneSeg.setEnabled(!custom);
            wpHalftoneSeg.setSelectedIndex(halftoneMode());
        }
        if (sfxSeg != null) {
            sfxSeg.setSelectedIndex(launcherSfxEnabled() ? 1 : 0);
        }
        if (hapticsSeg != null) {
            hapticsSeg.setSelectedIndex(launcherHapticsEnabled() ? 1 : 0);
        }
    }

    // ---- data validation ----

    boolean hasData() {
        return new File(dataDir(), "exefs/main").canRead() || new File(dataDir(), "analysis/main.bin").canRead();
    }

    static final String[] KNOWN_BUILDS = {
        "708BCACA873EA32B",  // US
        "B78A03E489E5BD92",  // EU
        "6E1F4CAE92A28C91",  // JP
        "2F2253ACD6BE5D87"   // Asia
    };

    String wrongBuild() {
        File main = new File(dataDir(), "exefs/main");
        if (!main.canRead()) return null;
        byte[] id = new byte[8];
        try (java.io.RandomAccessFile f = new java.io.RandomAccessFile(main, "r")) {
            f.seek(0x40);
            f.readFully(id);
        } catch (java.io.IOException e) {
            return "?";
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : id) hex.append(String.format("%02X", b & 0xFF));
        String have = hex.toString();
        for (String k : KNOWN_BUILDS) {
            if (have.equals(k)) return null;
        }
        return have;
    }

    // ---- lifecycle ----

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(lp);
        }
        hideSystemBars();
        P5Feedback.init(this);
        P5Feedback.setSfxEnabled(launcherSfxEnabled());
        P5Feedback.setHapticsEnabled(launcherHapticsEnabled());
        initOptions();
        boolean playIntro = (state == null);
        buildUi(playIntro);
        loadWallpaper();
        if (playIntro && Build.VERSION.SDK_INT >= 31) {
            Api31Helper.hookSplashExit(this, new Runnable() {
                @Override
                public void run() { startPendingIntro(); }
            });
        }
    }

    private void startPendingIntro() {
        if (introOverlay != null) {
            final P5Views.IntroOverlayView v = introOverlay;
            introOverlay = null;
            v.post(new Runnable() {
                @Override
                public void run() { v.play(); }
            });
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemBars();
            if (introOverlay != null) {
                if (Build.VERSION.SDK_INT >= 31) {
                    // Fallback if OS skipped splash exit callback (e.g., warm launch)
                    introOverlay.postDelayed(new Runnable() {
                        @Override
                        public void run() { startPendingIntro(); }
                    }, 180);
                } else {
                    startPendingIntro();
                }
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        hasPausedOnce = true;
        if (bgView != null) bgView.setHostResumed(false);
        stickDirX = 0;
        stickDirY = 0;
        stickRepeatHandler.removeCallbacks(stickRepeatTask);
    }

    @Override
    protected void onResume() {
        super.onResume();
        launchingGame = false;
        if (launchOverlay != null && rootFrame != null) {
            rootFrame.removeView(launchOverlay);
            launchOverlay = null;
        }
        hideSystemBars();
        refresh();
        if (bgView != null) {
            bgView.setHostResumed(true);
            if (hasPausedOnce) bgView.triggerSlash();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        P5Feedback.release();
        if (currentWallpaperBmp != null) {
            currentWallpaperBmp.recycle();
            currentWallpaperBmp = null;
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        View decor = getWindow().getDecorView();
        decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    private void initOptions() {
        // DISPLAY (Resolution & Scaling -> Image Enhancement -> Widescreen & Framing -> Language)
        options.add(new Option(TAB_DISPLAY, "RESOLUTION",
                "Resolución 3D", "3D Resolution",
                "Escala de renderizado 3D (la interfaz se mantiene nítida)",
                "3D scene render scale (UI stays crisp)",
                new String[] {"360p", "480p", "540p", "720p", "1080p"},
                new String[] {"360P", "480P", "540P", "720P", "1080P"},
                new String[] {"360P", "480P", "540P", "720P", "1080P"}, 3, false));
        options.add(new Option(TAB_DISPLAY, "NX_HUD_1080",
                "Resolución de interfaz", "Interface Resolution",
                "720p es más rápido; 1080p hace el texto más fino",
                "720p is faster; 1080p renders sharper menus and portraits",
                new String[] {"0", "1"},
                new String[] {"720P", "1080P"},
                new String[] {"720P", "1080P"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "SHARPEN_MODE",
                "Nitidez y reescalado", "Sharpening & Upscaling",
                "CAS es ligero y sin halos; FSR 1.0 reescala a resolución nativa",
                "CAS is fast & ring-free; FSR 1.0 upscales to native display",
                new String[] {"off", "cas", "fsr"},
                new String[] {"NO", "CAS", "FSR 1.0"},
                new String[] {"OFF", "CAS", "FSR 1.0"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "SHARPEN_STRENGTH",
                "Intensidad de nitidez", "Sharpening Strength",
                "Ajusta la fuerza de CAS o FSR 1.0 cuando está activo",
                "Tunes CAS or FSR 1.0 sharpening intensity when enabled",
                new String[] {"subtle", "med", "strong"},
                new String[] {"SUAVE", "MEDIO", "FUERTE"},
                new String[] {"SUBTLE", "MED", "STRONG"}, 1, false));
        options.add(new Option(TAB_DISPLAY, "NX_FXAA",
                "Suavizado de bordes (FXAA)", "Anti-Aliasing (FXAA)",
                "Suaviza los dientes de sierra 3D con bajo coste en GPU",
                "Smooths 3D stair-stepping jaggies with low GPU overhead",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "NX_COLOR_PROFILE",
                "Perfil de color", "Color Profile",
                "Realza vibrancia y contraste sin coste de rendimiento",
                "Zero-cost vibrance and contrast punch in the present pass",
                new String[] {"0", "1", "2"},
                new String[] {"NORMAL", "VÍVIDO", "P5 POP"},
                new String[] {"DEFAULT", "VIVID", "P5 POP"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "NX_ULTRAWIDE",
                "Pantalla ancha real (Hor+)", "True Widescreen (Hor+)",
                "Amplía el campo de visión horizontal en pantallas 20:9 / 21:9",
                "Expands horizontal 3D FOV to fill 20:9 / 21:9 displays",
                new String[] {"0", "1"},
                new String[] {"NO", "HOR+"},
                new String[] {"OFF", "HOR+"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "NX_UW_STRETCH_UI",
                "Interfaz en Hor+", "Hor+ UI Framing",
                "Mantener UI en 16:9 centrado o estirarla a los bordes",
                "Keep UI undistorted 16:9 centered or stretch edge-to-edge",
                new String[] {"0", "1"},
                new String[] {"16:9", "COMPLETA"},
                new String[] {"16:9", "FULL"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "NX_STRETCH",
                "Estirar imagen 16:9", "Fullscreen Stretch",
                "Estira la imagen 16:9 a toda la pantalla (desactivado en Hor+)",
                "Stretches 16:9 output to screen edges (disabled when Hor+ is on)",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 0, false));
        options.add(new Option(TAB_DISPLAY, "language",
                "Idioma de textos y subtítulos", "Text & Subtitle Language",
                "Idioma del texto del juego (las voces se eligen dentro del juego)",
                "In-game text language (voice track is chosen in-game)",
                new String[] {"auto", "en-US", "es", "fr", "de", "it", "ja", "ko", "zh-Hans", "zh-Hant"},
                new String[] {"Automático", "English", "Español", "Français", "Deutsch",
                        "Italiano", "日本語", "한국어", "简体中文", "繁體中文"},
                new String[] {"Automatic", "English", "Español", "Français", "Deutsch",
                        "Italiano", "日本語", "한국어", "简体中文", "繁體中文"}, 0, true));

        // PERFORMANCE (Engine & Pacing -> Visual Quality -> Diagnostics)
        options.add(new Option(TAB_PERFORMANCE, "NX_FPS_CAP",
                "Límite de FPS", "Frame Rate Cap",
                "30 fps es estable; 60 fps es experimental",
                "30 fps target for stability; 60 fps is experimental",
                new String[] {"30", "60"},
                new String[] {"30 FPS", "60 FPS"},
                new String[] {"30 FPS", "60 FPS"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_REPLAY_THREAD",
                "Hilo de replay asíncrono", "Async Replay Thread",
                "Separa la grabación de comandos del replay de Vulkan (más FPS)",
                "Overlaps command recording and Vulkan replay across Big cores",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_ASYNC_PIPELINES",
                "Compilación de shaders", "Shader Compilation",
                "Asíncrono evita tirones; Síncrono evita texturas blancas al entrar a zonas nuevas",
                "Async avoids hitches (surfaces pop in); Sync compiles on load (no white pop-in)",
                new String[] {"2", "0"},
                new String[] {"ASÍNCRONO", "SÍNCRONO"},
                new String[] {"ASYNC", "SYNC"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "GRAPHICS",
                "Calidad de texturas", "Texture Quality",
                "Sesgo de mipmaps (Nítido usa -0.5 para mayor detalle)",
                "Texture mipmap LOD bias (Sharp uses -0.5 for crisper detail)",
                new String[] {"low", "medium", "high", "sharp"},
                new String[] {"BAJO", "MEDIO", "ALTO", "NÍTIDO"},
                new String[] {"LOW", "MED", "HIGH", "SHARP"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_MAX_ANISO",
                "Filtro anisotrópico", "Anisotropic Filtering",
                "Claridad en suelos y calles en ángulo (4X es casi gratis en Mali)",
                "Sharpens angled floors, streets & walls (4X is nearly free)",
                new String[] {"auto", "2", "4", "8", "16"},
                new String[] {"AUTO", "2X", "4X", "8X", "16X"},
                new String[] {"AUTO", "2X", "4X", "8X", "16X"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_SHADOWS",
                "Sombras dinámicas", "Dynamic Shadows",
                "Desactivarlas ahorra ~9% de GPU en escenas 3D",
                "Disabling skips shadow map passes (~9% GPU savings)",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_MALI_LOW_SHADOWS",
                "Calidad de sombras", "Shadow Map Scale",
                "0.5x reduce el coste de sombras cuando están activas",
                "0.5x half-resolution shadows when shadows are enabled",
                new String[] {"1", "0"},
                new String[] {"0.5X", "1.0X"},
                new String[] {"0.5X", "1.0X"}, 0, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_DISABLE_BLOOM",
                "Post-procesado (Bloom)", "Bloom Post-Processing",
                "Desactivarlo omite 12 pasadas de desenfoque HDR por frame",
                "Disabling skips 12 HDR bloom blur passes per frame",
                new String[] {"1", "0"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 1, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_NO_CROWD",
                "Multitudes de NPCs", "Crowd NPCs",
                "Desactivarlas mejora los FPS en zonas llenas como Shibuya",
                "Disabling removes background crowds for higher FPS in cities",
                new String[] {"1", "0"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 1, false));
        options.add(new Option(TAB_PERFORMANCE, "NX_SHOW_FPS",
                "Contador de FPS", "FPS Counter",
                "Muestra la tasa de fotogramas en pantalla durante el juego",
                "Displays live frame rate overlay while playing",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 0, false));
        options.add(new Option(TAB_FILES, "NX_ENABLE_LOG",
                "Registro de depuración (nx.log)", "Debug Logging (nx.log)",
                "Graba detalles del motor y GPU en nx.log (los cierres se guardan siempre)",
                "Records engine & GPU details to nx.log (crashes are always logged automatically)",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 1, false));

        // CONTROLS (Touchscreen -> Gamepad)
        options.add(new Option(TAB_CONTROLS, "NX_TOUCH_SHOW",
                "Controles táctiles", "On-Screen Controls",
                "Habilita o desactiva el mando virtual táctil",
                "Enable or disable the virtual touchscreen overlay",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 1, false));
        options.add(new Option(TAB_CONTROLS, "NX_TOUCH_HIDE",
                "Ocultar controles táctiles", "Hide Touch Controls",
                "Oculta los gráficos táctiles manteniendo la entrada activa",
                "Keep touch controls invisible while still accepting touches",
                new String[] {"0", "1"},
                new String[] {"NO", "SÍ"},
                new String[] {"OFF", "ON"}, 0, false));
        options.add(new Option(TAB_CONTROLS, "NX_TOUCH_SIZE",
                "Tamaño de botones", "Button Size",
                "Escala de los botones virtuales en pantalla",
                "Scale of virtual touchscreen buttons",
                new String[] {"80", "100", "125"},
                new String[] {"PEQ", "NORMAL", "GRANDE"},
                new String[] {"SMALL", "NORMAL", "LARGE"}, 1, false));
        options.add(new Option(TAB_CONTROLS, "NX_TOUCH_OPACITY",
                "Opacidad de botones", "Button Opacity",
                "Transparencia del mando táctil sobre el juego",
                "Transparency of on-screen touch controls",
                new String[] {"50", "85", "100"},
                new String[] {"50%", "85%", "100%"},
                new String[] {"50%", "85%", "100%"}, 1, false));
        options.add(new Option(TAB_CONTROLS, "NX_STICK_SENSITIVITY",
                "Sensibilidad palanca táctil", "Touch Stick Sensitivity",
                "Velocidad de respuesta del stick izquierdo táctil",
                "Response multiplier for the virtual left analog stick",
                new String[] {"50", "75", "100", "125", "150", "200"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%"}, 2, true));
        options.add(new Option(TAB_CONTROLS, "NX_CAMERA_SENSITIVITY",
                "Sensibilidad cámara táctil", "Touch Camera Sensitivity",
                "Velocidad de giro de cámara al arrastrar en pantalla",
                "Rotation speed for touch camera drag",
                new String[] {"50", "75", "100", "125", "150", "200", "250"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"},
                new String[] {"50%", "75%", "100%", "125%", "150%", "200%", "250%"}, 2, true));
        options.add(new Option(TAB_CONTROLS, "NX_GAMEPAD_LAYOUT",
                "Distribución del mando físico", "Physical Gamepad Layout",
                "Mapeo de botones A/B/X/Y para mandos Bluetooth o USB",
                "Face button mapping for Bluetooth / USB controllers",
                new String[] {"0", "1"},
                new String[] {"NINTENDO", "XBOX / PC"},
                new String[] {"NINTENDO", "XBOX / PC"}, 0, false));
    }

    // ---- UI construction (Direction A: P5 Pause / Calling Card) ----

    private void buildUi(boolean playIntro) {
        FrameLayout root = new FrameLayout(this);
        rootFrame = root;
        root.setBackgroundColor(P5Theme.INK_BLACK);

        // Full-bleed background canvas
        bgView = new P5BackgroundView(this);
        root.addView(bgView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        // Foreground two-zone landscape deck (respects display cutouts)
        foregroundRoot = new LinearLayout(this);
        foregroundRoot.setOrientation(LinearLayout.HORIZONTAL);
        foregroundRoot.setClipChildren(false);
        foregroundRoot.setClipToPadding(false);
        root.addView(foregroundRoot, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        foregroundRoot.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left = 0, right = 0, top = 0, bottom = 0;
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout dc = insets.getDisplayCutout();
                    if (dc != null) {
                        left = dc.getSafeInsetLeft();
                        right = dc.getSafeInsetRight();
                        top = dc.getSafeInsetTop();
                        bottom = dc.getSafeInsetBottom();
                    }
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });

        // LEFT COMMAND RAIL
        LinearLayout leftRail = buildLeftRail();
        foregroundRoot.addView(leftRail, new LinearLayout.LayoutParams(
                dp(236), LinearLayout.LayoutParams.MATCH_PARENT));

        // RIGHT DYNAMIC STAGE + ADAPTIVE CONTROLLER HINT BAR
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setClipChildren(false);
        rightCol.setClipToPadding(false);
        rightCol.setPadding(dp(34), dp(14), dp(22), dp(10));
        foregroundRoot.addView(rightCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));

        FrameLayout rightStage = new FrameLayout(this);
        rightStage.setClipChildren(false);
        rightStage.setClipToPadding(false);
        rightCol.addView(rightStage, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        controllerHintBar = new P5Views.ControllerHintBar(this);
        controllerHintBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams hbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(26));
        hbLp.topMargin = dp(4);
        rightCol.addView(controllerHintBar, hbLp);

        tabPanels[TAB_PLAY] = buildPlayPanel();
        tabPanels[TAB_DISPLAY] = buildSettingsPanel(TAB_DISPLAY, t("PANTALLA", "DISPLAY"), "");
        tabPanels[TAB_PERFORMANCE] = buildSettingsPanel(TAB_PERFORMANCE, t("RENDIMIENTO", "PERFORMANCE"), "");
        tabPanels[TAB_CONTROLS] = buildControlsPanel();
        tabPanels[TAB_FILES] = buildFilesPanel();

        for (int i = 0; i < TAB_COUNT; i++) {
            tabPanels[i].setVisibility(i == TAB_PLAY ? View.VISIBLE : View.GONE);
            rightStage.addView(tabPanels[i], new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        if (playIntro) {
            introOverlay = new P5Views.IntroOverlayView(this);
            root.addView(introOverlay, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        }

        setContentView(root);
        selectTab(TAB_PLAY, false);
    }

    private LinearLayout buildLeftRail() {
        LinearLayout rail = new LinearLayout(this);
        rail.setOrientation(LinearLayout.VERTICAL);
        rail.setClipChildren(false);
        rail.setClipToPadding(false);
        rail.setPadding(dp(12), dp(12), dp(12), dp(12));

        // Ransom-cut P5R Masthead
        P5Views.CutTitleView masthead = new P5Views.CutTitleView(this);
        LinearLayout.LayoutParams mhLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58));
        mhLp.bottomMargin = dp(8);
        rail.addView(masthead, mhLp);

        // 5 Slanted Pause-Menu Tabs
        final String[] tabNames = {
            t("JUGAR", "PLAY"),
            t("PANTALLA", "DISPLAY"),
            t("RENDIMIENTO", "PERFORMANCE"),
            t("CONTROLES", "CONTROLS"),
            t("ARCHIVOS", "FILES")
        };
        final String[] tabNums = {"01", "02", "03", "04", "05"};

        LinearLayout navGroup = new LinearLayout(this);
        navGroup.setOrientation(LinearLayout.VERTICAL);
        navGroup.setClipChildren(false);
        for (int i = 0; i < TAB_COUNT; i++) {
            final int idx = i;
            P5Views.NavTabView tab = new P5Views.NavTabView(this, tabNames[i], tabNums[i]);
            tab.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(idx, true);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(40));
            lp.bottomMargin = dp(3);
            navGroup.addView(tab, lp);
            navTabs[i] = tab;
        }
        // Wrap navGroup in a lightweight flex spacer so bottom pinned Play sits cleanly at the foot
        ScrollView navScroll = new ScrollView(this);
        navScroll.setVerticalScrollBarEnabled(false);
        navScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        navScroll.setClipChildren(false);
        navScroll.addView(navGroup);
        rail.addView(navScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Hybrid Pinned PLAY button (hidden on TAB_PLAY, visible on all other tabs)
        pinnedPlay = new P5Views.SlashButton(this, t("JUGAR", "PLAY"), true, 46);
        pinnedPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onPlayClicked(); }
        });
        LinearLayout.LayoutParams ppLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        ppLp.topMargin = dp(6);
        ppLp.rightMargin = dp(18);
        rail.addView(pinnedPlay, ppLp);

        // Compact bottom version / alert stamp
        railStatusText = new TextView(this);
        railStatusText.setTypeface(P5Theme.monoBold());
        railStatusText.setTextSize(10.5f);
        railStatusText.setTextColor(P5Theme.MUTED_TEXT);
        railStatusText.setSingleLine(true);
        railStatusText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        railStatusText.setPadding(dp(6), dp(5), dp(14), 0);
        rail.addView(railStatusText);

        return rail;
    }

    private View buildPlayPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipChildren(false);

        // Top-right: Active Profile Spec Badges
        LinearLayout specBar = new LinearLayout(this);
        specBar.setOrientation(LinearLayout.HORIZONTAL);
        specBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        specBar.setClipChildren(false);

        specRes = addSpecBadge(specBar, "RES", "540P", true, TAB_DISPLAY, 0);
        specView = addSpecBadge(specBar, "VIEW", "16:9", false, TAB_DISPLAY, 2);
        specFps = addSpecBadge(specBar, "FPS", "30", false, TAB_PERFORMANCE, 0);
        specGfx = addSpecBadge(specBar, "GFX", "LOW", false, TAB_PERFORMANCE, 1);
        specExtra = addSpecBadge(specBar, "ENH", "OFF", false, TAB_DISPLAY, 1);
        addSpecBadge(specBar, "LOGS", "EXPORT", false, TAB_FILES, 2);
        panel.addView(specBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // Breathing space so procedural P5 art or custom wallpaper shines
        View spacer = new View(this);
        panel.addView(spacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // Bottom Launch Bar: READY TO PLAY status box directly beside Hero PLAY Slash Button
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomRow.setClipChildren(false);

        heroStatusCard = new P5Views.AngledRowLayout(this);
        heroStatusCard.setOrientation(LinearLayout.VERTICAL);
        heroStatusCard.setGravity(Gravity.CENTER_VERTICAL);
        heroStatusTitle = new TextView(this);
        heroStatusTitle.setTypeface(P5Theme.displayBold());
        heroStatusTitle.setTextSize(13f);
        heroStatusTitle.setSingleLine(true);
        heroStatusTitle.setTextColor(P5Theme.ACID_YELLOW);
        heroStatusCard.addView(heroStatusTitle);

        heroStatusBody = new TextView(this);
        heroStatusBody.setTypeface(P5Theme.bodyRegular());
        heroStatusBody.setTextSize(11.5f);
        heroStatusBody.setTextColor(P5Theme.PAPER_WHITE);
        heroStatusBody.setSingleLine(true);
        heroStatusBody.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        heroStatusCard.addView(heroStatusBody);

        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(0, dp(58), 1f);
        stLp.rightMargin = dp(12);
        bottomRow.addView(heroStatusCard, stLp);

        heroPlay = new P5Views.SlashButton(this, t("JUGAR", "PLAY"), true, 62);
        heroPlay.enableHeroSheen(true);
        heroPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { onPlayClicked(); }
        });
        LinearLayout.LayoutParams hpLp = new LinearLayout.LayoutParams(dp(236), dp(62));
        bottomRow.addView(heroPlay, hpLp);

        panel.addView(bottomRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return panel;
    }

    private P5Views.SpecBadge addSpecBadge(LinearLayout bar, String key, String val, boolean accent,
                                           final int targetTab, final int targetSubTab) {
        P5Views.SpecBadge b = new P5Views.SpecBadge(this, key, val, accent);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { selectTab(targetTab, targetSubTab, true); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(28));
        lp.leftMargin = dp(6);
        bar.addView(b, lp);
        return b;
    }

    private void addGroupHeader(LinearLayout parent, String label, boolean topGap) {
        P5Views.GroupSubHeaderView gh = new P5Views.GroupSubHeaderView(this, label);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(22));
        lp.topMargin = topGap ? dp(8) : 0;
        lp.bottomMargin = dp(4);
        parent.addView(gh, lp);
    }

    private void addHeaderWithSubTabs(LinearLayout col, final int tab, String title, String[] subLabels) {
        FrameLayout headerRow = new FrameLayout(this);
        headerRow.setClipChildren(false);
        headerRow.setClipToPadding(false);

        P5Views.SectionHeaderView hdr = new P5Views.SectionHeaderView(this, title, "");
        headerRow.addView(hdr, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(44)));

        P5Views.SubTabBar bar = new P5Views.SubTabBar(this, subLabels, 0, new P5Views.SubTabBar.OnSubTabListener() {
            @Override
            public void onPick(int index) {
                selectSubTab(tab, index, true);
            }
        });
        subTabBars[tab] = bar;
        subTabCount[tab] = subLabels.length;
        activeSubTab[tab] = 0;
        FrameLayout.LayoutParams bLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(32), Gravity.END | Gravity.CENTER_VERTICAL);
        bLp.bottomMargin = dp(2);
        headerRow.addView(bar, bLp);

        LinearLayout.LayoutParams hLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        hLp.bottomMargin = dp(6);
        col.addView(headerRow, hLp);
    }

    private void tagSubTabFrom(LinearLayout list, int startIndex, int subIdx) {
        for (int i = startIndex; i < list.getChildCount(); i++) {
            rowSubTabs.put(list.getChildAt(i), subIdx);
        }
    }

    private View buildSettingsPanel(int tab, String title, String subtitle) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        String[] subLabels = (tab == TAB_DISPLAY)
                ? new String[] {t("ESCALADO", "SCALING"), t("MEJORA", "ENHANCE"), t("ENCUADRE", "FRAMING")}
                : new String[] {t("MOTOR", "ENGINE"), t("CALIDAD", "QUALITY"), t("REGISTROS", "LOGS")};
        addHeaderWithSubTabs(col, tab, title, subLabels);

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(14));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(16));

        int curSub = 0;
        for (Option o : options) {
            if (o.tab != tab) continue;
            if (tab == TAB_DISPLAY) {
                if ("RESOLUTION".equals(o.key)) curSub = 0;
                else if ("NX_FXAA".equals(o.key)) curSub = 1;
                else if ("NX_ULTRAWIDE".equals(o.key)) curSub = 2;
            } else if (tab == TAB_PERFORMANCE) {
                if ("NX_FPS_CAP".equals(o.key)) curSub = 0;
                else if ("GRAPHICS".equals(o.key)) curSub = 1;
                else if ("NX_SHOW_FPS".equals(o.key)) curSub = 2;
            }
            int before = list.getChildCount();
            addOptionRow(list, o);
            tagSubTabFrom(list, before, curSub);
        }
        tabLists[tab] = list;
        tabScrolls[tab] = scroll;
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        applySubTabVisibility(tab, 0);
        return col;
    }

    private View buildControlsPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        addHeaderWithSubTabs(col, TAB_CONTROLS, t("CONTROLES", "CONTROLS"),
                new String[] {t("TÁCTIL", "TOUCH"), t("MANDO", "GAMEPAD")});

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(14));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(16));

        int before = list.getChildCount();
        Option touchShow = findOption("NX_TOUCH_SHOW");
        if (touchShow != null) addOptionRow(list, touchShow);

        // Move Controls Editor right beneath master On-Screen Controls toggle
        addActionRow(list,
                t("Posición de controles táctiles", "On-Screen Controls Layout"),
                t("Arrastra y acomoda los botones directamente en pantalla",
                        "Drag and position virtual buttons directly on screen"),
                t("EDITAR", "EDIT LAYOUT"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!hasAccess()) { askAccess(); return; }
                        save();
                        startActivity(new Intent(Launcher.this, ControlsEditor.class));
                    }
                }, null, null, true);
        tagSubTabFrom(list, before, 0);

        for (Option o : options) {
            if (o.tab != TAB_CONTROLS || "NX_TOUCH_SHOW".equals(o.key)) continue;
            int sub = "NX_GAMEPAD_LAYOUT".equals(o.key) ? 1 : 0;
            int b = list.getChildCount();
            addOptionRow(list, o);
            tagSubTabFrom(list, b, sub);
        }
        tabLists[TAB_CONTROLS] = list;
        tabScrolls[TAB_CONTROLS] = scroll;
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        applySubTabVisibility(TAB_CONTROLS, 0);
        return col;
    }

    private View buildFilesPanel() {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        addHeaderWithSubTabs(col, TAB_FILES, t("ARCHIVOS", "FILES"),
                new String[] {t("DATOS", "DATA"), t("APARIENCIA", "APPEARANCE"), t("REGISTROS", "LOGS")});

        ScrollView scroll = new ScrollView(this);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalFadingEdgeEnabled(true);
        scroll.setFadingEdgeLength(dp(14));
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(4), 0, dp(16));

        int filesDataStart = list.getChildCount();

        gameSub = addActionRow(list,
                t("Archivo del juego (.nsp / .xci)", "Game File (.nsp / .xci)"),
                t("Persona 5 Royal 1.0.0 base", "Persona 5 Royal 1.0.0 base dump"),
                t("ELEGIR", "CHOOSE"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pick(PICK_GAME, "*/*", false); }
                }, null, null, false);

        keysSub = addActionRow(list,
                t("Claves (prod.keys)", "Console Keys (prod.keys)"),
                t("Necesarias para leer archivos .nsp / .xci", "Required to decrypt .nsp / .xci files"),
                t("ELEGIR", "CHOOSE"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pick(PICK_KEYS, "*/*", true); }
                }, null, null, false);

        addActionRow(list,
                t("Partidas guardadas", "Save Data"),
                t("Importa desde ZIP o carpeta (JKSV / Checkpoint) o exporta a Descargas",
                        "Import ZIP/folder (JKSV / Checkpoint) or export backup to Download"),
                t("IMPORTAR", "IMPORT"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!hasAccess()) { askAccess(); return; }
                        askImport();
                    }
                },
                t("EXPORTAR", "EXPORT"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!hasAccess()) { askAccess(); return; }
                        exportSaves();
                    }
                }, false);

        addActionRow(list,
                t("Traducción / Mod (.cpk)", "Translation / CPK Mod"),
                t("Instala un paquete PATCH1.CPK en mods/translation/",
                        "Installs a PATCH1.CPK mod into mods/translation/"),
                t("IMPORTAR CPK", "IMPORT CPK"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        if (!hasAccess()) { askAccess(); return; }
                        pick(PICK_CPK, "*/*", false);
                    }
                }, null, null, false);

        driverSub = addActionRow(list,
                t("Driver de GPU personalizado", "Custom GPU Driver"),
                t("Driver del sistema", "System driver"),
                t("IMPORTAR", "IMPORT"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pick(PICK_DRIVER, "*/*", false); }
                },
                t("QUITAR", "REMOVE"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { removeDriver(); }
                }, false);

        tagSubTabFrom(list, filesDataStart, 0);
        int filesAppStart = list.getChildCount();

        wpSub = addActionRow(list,
                t("Fondo del lanzador", "Launcher Wallpaper"),
                t("Fondo por defecto", "Default Wallpaper"),
                t("ELEGIR", "CHOOSE"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pick(PICK_WALLPAPER, "image/*", false); }
                },
                t("RESTAURAR", "RESET"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { resetLauncherWallpaper(); }
                }, false);

        // Default Wallpaper Motion (Static / Animated — greyed out when a custom wallpaper is active)
        P5Views.AngledRowLayout motRow = new P5Views.AngledRowLayout(this);
        LinearLayout motTextCol = new LinearLayout(this);
        motTextCol.setOrientation(LinearLayout.VERTICAL);
        motTextCol.setPadding(dp(4), 0, dp(10), 0);
        TextView motTitle = new TextView(this);
        motTitle.setText(t("Movimiento del fondo", "Default Wallpaper Motion"));
        motTitle.setTypeface(P5Theme.condensedBold());
        motTitle.setTextSize(15.5f);
        motTitle.setTextColor(P5Theme.PAPER_WHITE);
        motTextCol.addView(motTitle);
        TextView motDesc = new TextView(this);
        motDesc.setText(t("Estático ahorra batería; Animado activa cintas y giroscopio",
                "Static freezes the default backdrop; Animated enables ribbons & gyro"));
        motDesc.setTypeface(P5Theme.bodyRegular());
        motDesc.setTextSize(11.5f);
        motDesc.setTextColor(P5Theme.MUTED_TEXT);
        motTextCol.addView(motDesc);
        motRow.addView(motTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String[] motLabels = new String[] {
            t("ESTÁTICO", "STATIC"), t("ANIMADO", "ANIMATED")
        };
        wpMotionSeg = new P5Views.SegmentedControl(this, motLabels, animatedDefaultWallpaper() ? 1 : 0, true,
                new P5Views.SegmentedControl.OnPickListener() {
                    @Override
                    public void onPick(int index) { setAnimatedDefaultWallpaper(index == 1); }
                });
        motRow.addView(wpMotionSeg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        wpMotionRow = motRow;
        LinearLayout.LayoutParams motLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        motLp.bottomMargin = dp(6);
        list.addView(motRow, motLp);

        // Default Wallpaper Halftone Style (active when Default Wallpaper is shown)
        P5Views.AngledRowLayout htRow = new P5Views.AngledRowLayout(this);
        LinearLayout htTextCol = new LinearLayout(this);
        htTextCol.setOrientation(LinearLayout.VERTICAL);
        htTextCol.setPadding(dp(4), 0, dp(10), 0);
        TextView htTitle = new TextView(this);
        htTitle.setText(t("Estilo de semitonos", "Halftone Style"));
        htTitle.setTypeface(P5Theme.condensedBold());
        htTitle.setTextSize(15.5f);
        htTitle.setTextColor(P5Theme.PAPER_WHITE);
        htTextCol.addView(htTitle);
        TextView htDesc = new TextView(this);
        htDesc.setText(t("Puntos Ben-Day del fondo por defecto",
                "Ben-Day dot treatment on the Default Wallpaper"));
        htDesc.setTypeface(P5Theme.bodyRegular());
        htDesc.setTextSize(11.5f);
        htDesc.setTextColor(P5Theme.MUTED_TEXT);
        htTextCol.addView(htDesc);
        htRow.addView(htTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String[] htLabels = new String[] {
            t("CINTAS", "RIBBONS"), t("ESTRELLA", "STAR HALO"), t("NO", "OFF")
        };
        wpHalftoneSeg = new P5Views.SegmentedControl(this, htLabels, halftoneMode(), false,
                new P5Views.SegmentedControl.OnPickListener() {
                    @Override
                    public void onPick(int index) { setHalftoneMode(index); }
                });
        htRow.addView(wpHalftoneSeg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        wpHalftoneRow = htRow;
        LinearLayout.LayoutParams htLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        htLp.bottomMargin = dp(6);
        list.addView(htRow, htLp);

        // Wallpaper Dim control (active when a custom wallpaper image is applied)
        P5Views.AngledRowLayout dimRow = new P5Views.AngledRowLayout(this);
        LinearLayout dimTextCol = new LinearLayout(this);
        dimTextCol.setOrientation(LinearLayout.VERTICAL);
        dimTextCol.setPadding(dp(4), 0, dp(10), 0);
        TextView dimTitle = new TextView(this);
        dimTitle.setText(t("Oscurecer fondo personalizado", "Wallpaper Dim"));
        dimTitle.setTypeface(P5Theme.condensedBold());
        dimTitle.setTextSize(15.5f);
        dimTitle.setTextColor(P5Theme.PAPER_WHITE);
        dimTextCol.addView(dimTitle);
        TextView dimDesc = new TextView(this);
        dimDesc.setText(t("Ajusta el contraste sobre tu imagen de fondo",
                "Controls overlay darkness over your custom wallpaper"));
        dimDesc.setTypeface(P5Theme.bodyRegular());
        dimDesc.setTextSize(11.5f);
        dimDesc.setTextColor(P5Theme.MUTED_TEXT);
        dimTextCol.addView(dimDesc);
        dimRow.addView(dimTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String[] dimLabels = new String[] {
            t("SUAVE", "LIGHT"), t("MEDIO", "MED"), t("OSCURO", "DARK")
        };
        wpDimSeg = new P5Views.SegmentedControl(this, dimLabels, wallpaperDim(), false,
                new P5Views.SegmentedControl.OnPickListener() {
                    @Override
                    public void onPick(int index) { setWallpaperDim(index); }
                });
        dimRow.addView(wpDimSeg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        wpDimRow = dimRow;
        LinearLayout.LayoutParams dimLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        dimLp.bottomMargin = dp(6);
        list.addView(dimRow, dimLp);

        // Launcher UI Sound Effects toggle
        P5Views.AngledRowLayout sfxRow = new P5Views.AngledRowLayout(this);
        LinearLayout sfxTextCol = new LinearLayout(this);
        sfxTextCol.setOrientation(LinearLayout.VERTICAL);
        sfxTextCol.setPadding(dp(4), 0, dp(10), 0);
        TextView sfxTitle = new TextView(this);
        sfxTitle.setText(t("Sonidos del lanzador", "Launcher Sound Effects"));
        sfxTitle.setTypeface(P5Theme.condensedBold());
        sfxTitle.setTextSize(15.5f);
        sfxTitle.setTextColor(P5Theme.PAPER_WHITE);
        sfxTextCol.addView(sfxTitle);
        TextView sfxDesc = new TextView(this);
        sfxDesc.setText(t("Efectos de clic y cambio de pestaña estilo P5",
                "P5-style UI clicks, tab swishes, and launch slash audio"));
        sfxDesc.setTypeface(P5Theme.bodyRegular());
        sfxDesc.setTextSize(11.5f);
        sfxDesc.setTextColor(P5Theme.MUTED_TEXT);
        sfxTextCol.addView(sfxDesc);
        sfxRow.addView(sfxTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String[] sfxLabels = new String[] { t("NO", "OFF"), t("SÍ", "ON") };
        sfxSeg = new P5Views.SegmentedControl(this, sfxLabels, launcherSfxEnabled() ? 1 : 0, true,
                new P5Views.SegmentedControl.OnPickListener() {
                    @Override
                    public void onPick(int index) { setLauncherSfxEnabled(index == 1); }
                });
        sfxRow.addView(sfxSeg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        LinearLayout.LayoutParams sfxLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sfxLp.bottomMargin = dp(6);
        list.addView(sfxRow, sfxLp);

        // Launcher Haptics toggle
        P5Views.AngledRowLayout hapRow = new P5Views.AngledRowLayout(this);
        LinearLayout hapTextCol = new LinearLayout(this);
        hapTextCol.setOrientation(LinearLayout.VERTICAL);
        hapTextCol.setPadding(dp(4), 0, dp(10), 0);
        TextView hapTitle = new TextView(this);
        hapTitle.setText(t("Vibración del lanzador", "Launcher Haptics"));
        hapTitle.setTypeface(P5Theme.condensedBold());
        hapTitle.setTextSize(15.5f);
        hapTitle.setTextColor(P5Theme.PAPER_WHITE);
        hapTextCol.addView(hapTitle);
        TextView hapDesc = new TextView(this);
        hapDesc.setText(t("Respuesta táctil al pulsar pestañas y botones",
                "Tactile vibration feedback on tabs, toggles, and PLAY"));
        hapDesc.setTypeface(P5Theme.bodyRegular());
        hapDesc.setTextSize(11.5f);
        hapDesc.setTextColor(P5Theme.MUTED_TEXT);
        hapTextCol.addView(hapDesc);
        hapRow.addView(hapTextCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        String[] hapLabels = new String[] { t("NO", "OFF"), t("SÍ", "ON") };
        hapticsSeg = new P5Views.SegmentedControl(this, hapLabels, launcherHapticsEnabled() ? 1 : 0, true,
                new P5Views.SegmentedControl.OnPickListener() {
                    @Override
                    public void onPick(int index) { setLauncherHapticsEnabled(index == 1); }
                });
        hapRow.addView(hapticsSeg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        LinearLayout.LayoutParams hapLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hapLp.bottomMargin = dp(6);
        list.addView(hapRow, hapLp);

        addActionRow(list,
                t("Acceso directo con icono propio", "Custom Home Shortcut Icon"),
                t("Crea un acceso directo en tu pantalla de inicio con tu imagen",
                        "Pins a home screen shortcut using your own picture"),
                t("ELEGIR", "CHOOSE"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { pick(PICK_ICON, "image/*", false); }
                }, null, null, false);

        tagSubTabFrom(list, filesAppStart, 1);
        int filesLogsStart = list.getChildCount();

        Option dbgLogOpt = findOption("NX_ENABLE_LOG");
        if (dbgLogOpt != null) addOptionRow(list, dbgLogOpt);

        addActionRow(list,
                t("Registros de depuración", "Debug Logs"),
                t("Exporta equipo, ajustes, cierres, nx.log y logcat a Download/p5r-log.txt",
                        "Exports device info, settings, crash log, nx.log & logcat to Download/p5r-log.txt"),
                t("EXPORTAR", "EXPORT LOGS"),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) { exportLogs(); }
                }, null, null, true);

        tagSubTabFrom(list, filesLogsStart, 2);
        tabLists[TAB_FILES] = list;
        tabScrolls[TAB_FILES] = scroll;
        scroll.addView(list);
        col.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        applySubTabVisibility(TAB_FILES, 0);
        return col;
    }

    private void addOptionRow(LinearLayout parent, final Option o) {
        P5Views.AngledRowLayout row = new P5Views.AngledRowLayout(this);
        o.rowView = row;

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(4), 0, dp(10), 0);

        TextView title = new TextView(this);
        title.setText(t(o.es, o.en));
        title.setTypeface(P5Theme.condensedBold());
        title.setTextSize(15.5f);
        title.setTextColor(P5Theme.PAPER_WHITE);
        textCol.addView(title);

        TextView desc = new TextView(this);
        desc.setText(t(o.descEs, o.descEn));
        desc.setTypeface(P5Theme.bodyRegular());
        desc.setTextSize(11.5f);
        desc.setTextColor(P5Theme.MUTED_TEXT);
        textCol.addView(desc);

        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        if (o.modal) {
            final P5Views.SlashButton btn = new P5Views.SlashButton(this, label(o) + " ▾", false, 38);
            btn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    P5Views.showPickerDialog(Launcher.this, t(o.es, o.en), labelsOf(o), o.pick,
                            new P5Views.OnModalPick() {
                                @Override
                                public void onPick(int index) {
                                    o.pick = index;
                                    save();
                                    syncOptionViews();
                                }
                            });
                }
            });
            o.modalBtn = btn;
            row.addView(btn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(38)));
        } else {
            boolean isOffOn = (o.labelsEn.length == 2 && "OFF".equals(o.labelsEn[0]));
            P5Views.SegmentedControl seg = new P5Views.SegmentedControl(this, labelsOf(o), o.pick, isOffOn,
                    new P5Views.SegmentedControl.OnPickListener() {
                        @Override
                        public void onPick(int index) {
                            o.pick = index;
                            save();
                            syncOptionViews();
                        }
                    });
            o.segView = seg;
            row.addView(seg, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)));
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        parent.addView(row, lp);
    }

    private TextView addActionRow(LinearLayout parent, String titleText, String subText,
                                  String btn1Label, View.OnClickListener click1,
                                  String btn2Label, View.OnClickListener click2, boolean highlight) {
        P5Views.AngledRowLayout row = new P5Views.AngledRowLayout(this);
        row.setHighlight(highlight);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setPadding(dp(4), 0, dp(10), 0);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTypeface(P5Theme.condensedBold());
        title.setTextSize(15.5f);
        title.setTextColor(P5Theme.PAPER_WHITE);
        textCol.addView(title);

        TextView sub = new TextView(this);
        sub.setText(subText);
        sub.setTypeface(P5Theme.bodyRegular());
        sub.setTextSize(11.5f);
        sub.setTextColor(P5Theme.MUTED_TEXT);
        textCol.addView(sub);

        row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        P5Views.SlashButton b1 = new P5Views.SlashButton(this, btn1Label, highlight, 38);
        b1.setOnClickListener(click1);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
        row.addView(b1, lp1);

        if (btn2Label != null && click2 != null) {
            P5Views.SlashButton b2 = new P5Views.SlashButton(this, btn2Label, false, 38);
            b2.setOnClickListener(click2);
            LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(38));
            lp2.leftMargin = dp(6);
            row.addView(b2, lp2);
        }

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        parent.addView(row, lp);
        return sub;
    }

    // ---- Tab switching & Hybrid Pinned Play logic ----

    private void applySubTabVisibility(int tab, int subIdx) {
        LinearLayout list = (tab >= 0 && tab < TAB_COUNT) ? tabLists[tab] : null;
        if (list == null) return;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            Integer s = rowSubTabs.get(child);
            if (s != null) {
                child.setVisibility(s == subIdx ? View.VISIBLE : View.GONE);
            }
        }
    }

    private void animateVisibleRows(int tab) {
        LinearLayout list = (tab >= 0 && tab < TAB_COUNT) ? tabLists[tab] : null;
        if (list == null) return;
        int vis = 0;
        for (int r = 0; r < list.getChildCount(); r++) {
            View row = list.getChildAt(r);
            if (row.getVisibility() != View.VISIBLE) continue;
            row.animate().cancel();
            Object tag = row.getTag();
            float targetAlpha = (tag instanceof Float) ? (Float) tag : 1.0f;
            if (vis < 8) {
                row.setAlpha(0f);
                row.setTranslationX(dp(20));
                row.animate().alpha(targetAlpha).translationX(0f)
                        .setStartDelay(vis * 22L)
                        .setDuration(145)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            } else {
                row.setAlpha(targetAlpha);
                row.setTranslationX(0f);
            }
            vis++;
        }
    }

    private void selectSubTab(int tab, int subIdx, boolean animate) {
        if (tab < 0 || tab >= TAB_COUNT || subTabCount[tab] <= 0) return;
        int clamped = Math.max(0, Math.min(subTabCount[tab] - 1, subIdx));
        if (animate && clamped == activeSubTab[tab]) return;
        activeSubTab[tab] = clamped;
        if (subTabBars[tab] != null) subTabBars[tab].setSelected(clamped);
        applySubTabVisibility(tab, clamped);
        if (tabScrolls[tab] != null) tabScrolls[tab].scrollTo(0, 0);
        if (animate) animateVisibleRows(tab);
        focusedRowIdx = 0;
        focusedBtnIdx = 0;
        if (controllerMode && tab == activeTab) {
            refreshControllerFocus(false);
            updateControllerHints();
        }
    }

    private void selectTab(int idx, boolean animate) {
        selectTab(idx, 0, animate);
    }

    private void selectTab(int idx, int subIdx, boolean animate) {
        if (animate && idx == activeTab && (subTabCount[idx] == 0 || subIdx == activeSubTab[idx])) return;
        boolean sameTab = (idx == activeTab);
        activeTab = idx;
        if (subTabCount[idx] > 0) {
            int clamped = Math.max(0, Math.min(subTabCount[idx] - 1, subIdx));
            activeSubTab[idx] = clamped;
            if (subTabBars[idx] != null) subTabBars[idx].setSelected(clamped);
            applySubTabVisibility(idx, clamped);
        }
        if (sameTab && animate) {
            if (tabScrolls[idx] != null) tabScrolls[idx].scrollTo(0, 0);
            animateVisibleRows(idx);
            focusedRowIdx = 0;
            focusedBtnIdx = 0;
            if (controllerMode) {
                refreshControllerFocus(false);
                updateControllerHints();
            }
            return;
        }
        if (animate) {
            P5Feedback.tab(this);
        }
        for (int i = 0; i < TAB_COUNT; i++) {
            navTabs[i].setActive(i == idx, animate);
            boolean show = (i == idx);
            View panel = tabPanels[i];
            if (show) {
                panel.setVisibility(View.VISIBLE);
                if (tabScrolls[i] != null) tabScrolls[i].scrollTo(0, 0);
                LinearLayout list = tabLists[i];
                if (animate && list != null) {
                    panel.animate().cancel();
                    panel.setAlpha(1f);
                    panel.setTranslationX(0f);
                    // Crisp slide-in on the section header
                    if (panel instanceof ViewGroup && ((ViewGroup) panel).getChildCount() > 0) {
                        View hdr = ((ViewGroup) panel).getChildAt(0);
                        hdr.animate().cancel();
                        hdr.setAlpha(0f);
                        hdr.setTranslationX(dp(14));
                        hdr.animate().alpha(1f).translationX(0f)
                                .setDuration(140).setInterpolator(new DecelerateInterpolator()).start();
                    }
                    // Staggered kinetic entrance for visible settings rows in active sub-tab
                    animateVisibleRows(i);
                } else if (animate) {
                    panel.animate().cancel();
                    panel.setAlpha(0f);
                    panel.setTranslationX(dp(18));
                    panel.animate().alpha(1f).translationX(0f)
                            .setDuration(170).setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    panel.setAlpha(1f);
                    panel.setTranslationX(0f);
                }
            } else {
                panel.setVisibility(View.GONE);
            }
        }

        // Hybrid Play button: hidden on TAB_PLAY (Hero Play is on stage), pinned on Settings tabs
        boolean showPinned = (idx != TAB_PLAY);
        if (showPinned) {
            boolean wasHidden = pinnedPlay.getVisibility() != View.VISIBLE;
            pinnedPlay.setVisibility(View.VISIBLE);
            if (animate && wasHidden) {
                pinnedPlay.setAlpha(0f);
                pinnedPlay.setTranslationY(dp(14));
                pinnedPlay.animate().alpha(1f).translationY(0f)
                        .setDuration(160).setInterpolator(new DecelerateInterpolator()).start();
            } else {
                pinnedPlay.setAlpha(1f);
                pinnedPlay.setTranslationY(0f);
            }
        } else {
            pinnedPlay.setVisibility(View.GONE);
        }

        bgView.setSettingsMode(idx != TAB_PLAY, animate);

        focusedRowIdx = 0;
        focusedBtnIdx = 0;
        if (controllerMode) {
            refreshControllerFocus(false);
            updateControllerHints();
        }
    }

    // ---- Physical Controller Navigation Engine ----

    private void setControllerMode(boolean active) {
        if (controllerMode == active) return;
        controllerMode = active;
        P5Views.setControllerModeActive(active);
        for (int t = 0; t < TAB_COUNT; t++) {
            if (subTabBars[t] != null) subTabBars[t].setShowTriggers(active);
        }
        if (controllerHintBar != null) {
            controllerHintBar.setVisibility(active ? View.VISIBLE : View.GONE);
        }
        if (active) {
            refreshControllerFocus(true);
            updateControllerHints();
        } else {
            clearControllerFocusVisuals();
        }
    }

    private void clearControllerFocusVisuals() {
        if (heroPlay != null) heroPlay.setControllerFocused(false);
        for (int t = 0; t < TAB_COUNT; t++) {
            LinearLayout list = tabLists[t];
            if (list == null) continue;
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (child instanceof P5Views.AngledRowLayout) {
                    P5Views.AngledRowLayout row = (P5Views.AngledRowLayout) child;
                    row.setControllerFocused(false);
                    setRowChildFocusVisuals(row, false, -1);
                }
            }
        }
    }

    private boolean isRowInteractive(P5Views.AngledRowLayout row) {
        if (row.getVisibility() != View.VISIBLE) return false;
        Object tag = row.getTag();
        if (tag instanceof Float && ((Float) tag) < 0.7f) return false;
        if (row.getAlpha() < 0.7f && tag == null) return false;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if ((c instanceof P5Views.SegmentedControl || c instanceof P5Views.SlashButton) && c.isEnabled()) {
                return true;
            }
        }
        return false;
    }

    private List<P5Views.AngledRowLayout> getInteractiveRows(int tab) {
        List<P5Views.AngledRowLayout> out = new ArrayList<>();
        LinearLayout list = (tab >= 0 && tab < TAB_COUNT) ? tabLists[tab] : null;
        if (list == null) return out;
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof P5Views.AngledRowLayout && isRowInteractive((P5Views.AngledRowLayout) child)) {
                out.add((P5Views.AngledRowLayout) child);
            }
        }
        return out;
    }

    private P5Views.SegmentedControl findSegmentedInRow(P5Views.AngledRowLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof P5Views.SegmentedControl && c.isEnabled()) return (P5Views.SegmentedControl) c;
        }
        return null;
    }

    private List<P5Views.SlashButton> findButtonsInRow(P5Views.AngledRowLayout row) {
        List<P5Views.SlashButton> out = new ArrayList<>();
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof P5Views.SlashButton && c.isEnabled()) out.add((P5Views.SlashButton) c);
        }
        return out;
    }

    private void setRowChildFocusVisuals(P5Views.AngledRowLayout row, boolean rowFocused, int btnIdx) {
        int seenBtn = 0;
        for (int i = 0; i < row.getChildCount(); i++) {
            View c = row.getChildAt(i);
            if (c instanceof P5Views.SegmentedControl) {
                ((P5Views.SegmentedControl) c).setControllerFocused(rowFocused);
            } else if (c instanceof P5Views.SlashButton) {
                boolean bf = rowFocused && (seenBtn == btnIdx);
                ((P5Views.SlashButton) c).setControllerFocused(bf);
                seenBtn++;
            }
        }
    }

    private void refreshControllerFocus(boolean scrollIntoView) {
        clearControllerFocusVisuals();
        if (!controllerMode) return;
        if (activeTab == TAB_PLAY) {
            if (heroPlay != null) heroPlay.setControllerFocused(true);
            return;
        }
        List<P5Views.AngledRowLayout> rows = getInteractiveRows(activeTab);
        if (rows.isEmpty()) return;
        if (focusedRowIdx < 0) focusedRowIdx = 0;
        if (focusedRowIdx >= rows.size()) focusedRowIdx = rows.size() - 1;
        final P5Views.AngledRowLayout row = rows.get(focusedRowIdx);
        List<P5Views.SlashButton> btns = findButtonsInRow(row);
        if (focusedBtnIdx < 0 || focusedBtnIdx >= btns.size()) focusedBtnIdx = 0;
        row.setControllerFocused(true);
        setRowChildFocusVisuals(row, true, focusedBtnIdx);

        if (scrollIntoView && tabScrolls[activeTab] != null) {
            final ScrollView sc = tabScrolls[activeTab];
            sc.post(new Runnable() {
                @Override
                public void run() {
                    int targetY = Math.max(0, row.getTop() - sc.getHeight() / 2 + row.getHeight() / 2);
                    sc.smoothScrollTo(0, targetY);
                }
            });
        }
    }

    private void updateControllerHints() {
        if (controllerHintBar == null) return;
        String okLbl = "A";
        String backLbl = "B";
        if (activeTab == TAB_PLAY) {
            controllerHintBar.setSplitHints(
                    new String[] {okLbl + " / START"},
                    new String[] {t("JUGAR", "PLAY")},
                    new String[] {"LB / RB", "▲▼"},
                    new String[] {t("PESTAÑAS", "TABS"), t("MENÚ", "MENU")});
            return;
        }
        List<P5Views.AngledRowLayout> rows = getInteractiveRows(activeTab);
        P5Views.AngledRowLayout row = (!rows.isEmpty() && focusedRowIdx >= 0 && focusedRowIdx < rows.size())
                ? rows.get(focusedRowIdx) : null;
        boolean hasSeg = row != null && findSegmentedInRow(row) != null;
        List<P5Views.SlashButton> btns = row != null ? findButtonsInRow(row) : java.util.Collections.<P5Views.SlashButton>emptyList();

        // Fixed right-aligned global controls (never shift horizontally when moving Up/Down)
        String[] rk = new String[] {"LB / RB", backLbl, "START"};
        String[] rl = new String[] {t("PESTAÑAS", "TABS"), t("INICIO", "HOME"), t("JUGAR", "PLAY")};

        // Contextual left-aligned row action only
        if (hasSeg) {
            controllerHintBar.setSplitHints(
                    new String[] {"◀▶"},
                    new String[] {t("AJUSTAR", "ADJUST")},
                    rk, rl);
        } else if (btns.size() > 1) {
            controllerHintBar.setSplitHints(
                    new String[] {"◀▶", okLbl},
                    new String[] {t("BOTÓN", "BTN"), t("OK", "SELECT")},
                    rk, rl);
        } else {
            controllerHintBar.setSplitHints(
                    new String[] {okLbl},
                    new String[] {t("ABRIR", "OPEN")},
                    rk, rl);
        }
    }

    private void onControllerVertical(int dir) {
        setControllerMode(true);
        if (activeTab == TAB_PLAY) {
            int nextTab = (activeTab + dir + TAB_COUNT) % TAB_COUNT;
            selectTab(nextTab, true);
            return;
        }
        List<P5Views.AngledRowLayout> rows = getInteractiveRows(activeTab);
        if (rows.isEmpty()) return;
        focusedRowIdx = (focusedRowIdx + dir + rows.size()) % rows.size();
        focusedBtnIdx = 0;
        P5Feedback.tick(this);
        refreshControllerFocus(true);
        updateControllerHints();
    }

    private void onControllerHorizontal(int dir) {
        setControllerMode(true);
        if (activeTab == TAB_PLAY) return;
        List<P5Views.AngledRowLayout> rows = getInteractiveRows(activeTab);
        if (rows.isEmpty() || focusedRowIdx >= rows.size()) return;
        P5Views.AngledRowLayout row = rows.get(focusedRowIdx);
        P5Views.SegmentedControl seg = findSegmentedInRow(row);
        if (seg != null) {
            seg.step(dir);
            return;
        }
        List<P5Views.SlashButton> btns = findButtonsInRow(row);
        if (btns.size() > 1) {
            int next = (focusedBtnIdx + dir + btns.size()) % btns.size();
            if (next != focusedBtnIdx) {
                focusedBtnIdx = next;
                P5Feedback.tick(this);
                refreshControllerFocus(false);
            }
        }
    }

    private void fireTriggerOnce(int dir) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTriggerMs < 180L) return;
        lastTriggerMs = now;
        onControllerSectionJump(dir);
    }

    private void onControllerSectionJump(int dir) {
        setControllerMode(true);
        if (activeTab == TAB_PLAY || subTabCount[activeTab] <= 1) return;
        int cur = activeSubTab[activeTab];
        int next = Math.max(0, Math.min(subTabCount[activeTab] - 1, cur + dir));
        if (next == cur) return;
        P5Feedback.tab(this);
        selectSubTab(activeTab, next, true);
    }

    private void onControllerConfirm() {
        setControllerMode(true);
        if (activeTab == TAB_PLAY) {
            onPlayClicked();
            return;
        }
        List<P5Views.AngledRowLayout> rows = getInteractiveRows(activeTab);
        if (rows.isEmpty() || focusedRowIdx >= rows.size()) return;
        P5Views.AngledRowLayout row = rows.get(focusedRowIdx);
        P5Views.SegmentedControl seg = findSegmentedInRow(row);
        if (seg != null) {
            seg.cycle();
            return;
        }
        List<P5Views.SlashButton> btns = findButtonsInRow(row);
        if (!btns.isEmpty()) {
            int bi = Math.max(0, Math.min(btns.size() - 1, focusedBtnIdx));
            btns.get(bi).performClick();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN && controllerMode) {
            setControllerMode(false);
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        boolean handledKey =
                keyCode == KeyEvent.KEYCODE_BUTTON_L1 || keyCode == KeyEvent.KEYCODE_BUTTON_R1
                || keyCode == KeyEvent.KEYCODE_BUTTON_L2 || keyCode == KeyEvent.KEYCODE_BUTTON_R2
                || keyCode == KeyEvent.KEYCODE_BUTTON_START
                || keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_BUTTON_B
                || keyCode == KeyEvent.KEYCODE_BUTTON_X || keyCode == KeyEvent.KEYCODE_BUTTON_Y;

        if (!handledKey) return super.dispatchKeyEvent(event);
        if (action == KeyEvent.ACTION_UP) {
            if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) ltDown = false;
            if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) rtDown = false;
            return true;
        }
        if (action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event);

        // Never repeat bumpers, triggers, Start, Confirm, or Back on hold
        boolean allowRepeat = (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT);
        if (!allowRepeat && event.getRepeatCount() > 0) return true;

        if (keyCode == KeyEvent.KEYCODE_BUTTON_L1) {
            setControllerMode(true);
            selectTab((activeTab + TAB_COUNT - 1) % TAB_COUNT, true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R1) {
            setControllerMode(true);
            selectTab((activeTab + 1) % TAB_COUNT, true);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_L2) {
            if (!ltDown) {
                ltDown = true;
                fireTriggerOnce(-1);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_R2) {
            if (!rtDown) {
                rtDown = true;
                fireTriggerOnce(1);
            }
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START) {
            setControllerMode(true);
            onPlayClicked();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP) {
            onControllerVertical(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
            onControllerVertical(1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            onControllerHorizontal(-1);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            onControllerHorizontal(1);
            return true;
        }
        if (P5Views.isConfirmKey(keyCode)) {
            onControllerConfirm();
            return true;
        }
        if (P5Views.isBackKey(keyCode)) {
            setControllerMode(true);
            if (activeTab != TAB_PLAY) {
                P5Feedback.cancel(this);
                selectTab(TAB_PLAY, true);
            }
            return true;
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if ((event.getSource() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
                || (event.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) {
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float lx = event.getAxisValue(MotionEvent.AXIS_X);
            float ly = event.getAxisValue(MotionEvent.AXIS_Y);
            float x = Math.abs(hatX) > 0.3f ? hatX : lx;
            float y = Math.abs(hatY) > 0.3f ? hatY : ly;

            int newY = (y > 0.55f) ? 1 : (y < -0.55f) ? -1 : (Math.abs(y) < 0.30f ? 0 : stickDirY);
            int newX = (x > 0.55f) ? 1 : (x < -0.55f) ? -1 : (Math.abs(x) < 0.30f ? 0 : stickDirX);

            if (newY != stickDirY) {
                stickDirY = newY;
                stickRepeatHandler.removeCallbacks(stickRepeatTask);
                if (stickDirY != 0) {
                    onControllerVertical(stickDirY);
                    stickRepeatHandler.postDelayed(stickRepeatTask, 320L);
                }
            }
            if (newX != stickDirX) {
                stickDirX = newX;
                if (stickDirY == 0) {
                    stickRepeatHandler.removeCallbacks(stickRepeatTask);
                    if (stickDirX != 0) {
                        onControllerHorizontal(stickDirX);
                        stickRepeatHandler.postDelayed(stickRepeatTask, 320L);
                    }
                }
            }

            // Analog triggers L2 / R2 for sub-tab switching (strictly 1 step per pull, shared latch with digital L2/R2)
            float lt = Math.max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE));
            float rt = Math.max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS));
            if (lt > 0.55f && !ltDown) {
                ltDown = true;
                fireTriggerOnce(-1);
            } else if (lt < 0.20f) {
                ltDown = false;
            }
            if (rt > 0.55f && !rtDown) {
                rtDown = true;
                fireTriggerOnce(1);
            } else if (rt < 0.20f) {
                rtDown = false;
            }

            // Right stick vertical smooth scroll on active settings tab
            float rz = event.getAxisValue(MotionEvent.AXIS_RZ);
            if (Math.abs(rz) > 0.22f && activeTab != TAB_PLAY && tabScrolls[activeTab] != null) {
                tabScrolls[activeTab].scrollBy(0, Math.round(rz * dp(18)));
            }
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    // ---- Launch action ----

    private void launchGameWithSlash() {
        if (launchingGame) return;
        launchingGame = true;
        P5Feedback.confirm(this);
        save();
        if (rootFrame == null) {
            startActivity(new Intent(Launcher.this, android.app.NativeActivity.class));
            return;
        }
        if (launchOverlay != null) rootFrame.removeView(launchOverlay);
        launchOverlay = new P5Views.LaunchSlashOverlayView(this);
        rootFrame.addView(launchOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        launchOverlay.play(new Runnable() {
            @Override
            public void run() {
                startActivity(new Intent(Launcher.this, android.app.NativeActivity.class));
                overridePendingTransition(0, 0);
            }
        });
    }

    private void onPlayClicked() {
        if (launchingGame) return;
        if (!hasAccess()) {
            askAccess();
            return;
        }
        if (gameUri() != null) {
            if (!gameReadable()) pick(PICK_GAME, "*/*", false);
            else if (!hasKeys()) pick(PICK_KEYS, "*/*", true);
            else if (!prepared()) prepare();
            else if (openGameForRuntime()) {
                launchGameWithSlash();
            } else {
                tell(t("No se pudo abrir el juego", "The game could not be opened"), gameName());
            }
            return;
        }
        if (!hasData()) {
            pick(PICK_GAME, "*/*", false);
            return;
        }
        if (wrongBuild() != null) {
            refresh();
            return;
        }
        launchGameWithSlash();
    }

    // ---- State sync ----

    void refresh() {
        load();
        if (!prefs().getBoolean("nx_log_default_on_v1", false)) {
            prefs().edit().putBoolean("nx_log_default_on_v1", true).apply();
            config.put("NX_ENABLE_LOG", "1");
            if (hasAccess() && dataDir().isDirectory()) {
                writeConfig(new File(dataDir(), "config.txt"), config);
            }
        }
        for (Option o : options) {
            String v = config.get(o.key);
            if ("RESOLUTION".equals(o.key) && v == null) {
                String hh = config.get("NX_HANDHELD");
                if ("0".equals(hh)) v = "1080p";
                else {
                    String r = config.get("resolution");
                    if (r != null && r.contains("360")) v = "360p";
                    else if (r != null && r.contains("480")) v = "480p";
                    else if (r != null && r.contains("540")) v = "540p";
                    else v = "720p";
                }
            }
            if ("SHARPEN_MODE".equals(o.key) && v == null) {
                if ("1".equals(config.get("NX_FSR"))) v = "fsr";
                else if ("1".equals(config.get("NX_CAS"))) v = "cas";
                else v = "off";
            }
            if ("NX_MAX_ANISO".equals(o.key) && v == null) {
                v = "auto";
            }
            for (int k = 0; v != null && k < o.values.length; k++) {
                if (o.values[k].equals(v)) o.pick = k;
            }
        }
        syncOptionViews();
        updateWallpaperUi();
        showDriver();
        updateReadiness();
    }

    private void syncOptionViews() {
        Option shadows = findOption("NX_SHADOWS");
        boolean shadowsOn = shadows != null && "1".equals(shadows.values[shadows.pick]);
        Option uw = findOption("NX_ULTRAWIDE");
        boolean uwOn = uw != null && "1".equals(uw.values[uw.pick]);
        Option stretchOpt = findOption("NX_STRETCH");
        if (uwOn && stretchOpt != null) stretchOpt.pick = 0;
        Option touchShow = findOption("NX_TOUCH_SHOW");
        boolean touchOn = touchShow == null || "1".equals(touchShow.values[touchShow.pick]);
        Option sharpModeOpt = findOption("SHARPEN_MODE");
        boolean sharpenOn = sharpModeOpt != null && !"off".equals(sharpModeOpt.values[sharpModeOpt.pick]);
        Option gpLayoutOpt = findOption("NX_GAMEPAD_LAYOUT");
        P5Views.setXboxGamepadLayout(gpLayoutOpt != null && "1".equals(gpLayoutOpt.values[gpLayoutOpt.pick]));

        for (Option o : options) {
            if (o.segView != null) o.segView.setSelectedIndex(o.pick);
            if (o.modalBtn != null) o.modalBtn.setText(label(o) + " ▾");

            boolean enabled = true;
            if ("NX_MALI_LOW_SHADOWS".equals(o.key)) enabled = shadowsOn;
            else if ("SHARPEN_STRENGTH".equals(o.key)) enabled = sharpenOn;
            else if ("NX_UW_STRETCH_UI".equals(o.key)) enabled = uwOn;
            else if ("NX_STRETCH".equals(o.key)) enabled = !uwOn;
            else if ("NX_TOUCH_HIDE".equals(o.key)
                    || "NX_TOUCH_SIZE".equals(o.key)
                    || "NX_TOUCH_OPACITY".equals(o.key)
                    || "NX_STICK_SENSITIVITY".equals(o.key)
                    || "NX_CAMERA_SENSITIVITY".equals(o.key)) enabled = touchOn;

            if (o.rowView != null) {
                float alpha = enabled ? 1.0f : 0.36f;
                o.rowView.setTag(alpha);
                o.rowView.setAlpha(alpha);
            }
            if (o.segView != null) o.segView.setEnabled(enabled);
            if (o.modalBtn != null) o.modalBtn.setEnabled(enabled);
        }

        // Update Home hero spec badges
        Option resOpt = findOption("RESOLUTION");
        Option fpsOpt = findOption("NX_FPS_CAP");
        Option gfxOpt = findOption("GRAPHICS");
        Option sharpOpt = findOption("SHARPEN_MODE");
        Option fxaaOpt = findOption("NX_FXAA");

        if (specRes != null && resOpt != null) {
            specRes.setValue(resOpt.values[resOpt.pick].toUpperCase(Locale.ROOT), true);
        }
        if (specView != null) {
            String viewVal = uwOn ? "HOR+" : (stretchOpt != null && "1".equals(stretchOpt.values[stretchOpt.pick]) ? "STRETCH" : "16:9");
            specView.setValue(viewVal, uwOn);
        }
        if (specFps != null && fpsOpt != null) {
            specFps.setValue(fpsOpt.values[fpsOpt.pick], false);
        }
        if (specGfx != null && gfxOpt != null) {
            specGfx.setValue(gfxOpt.values[gfxOpt.pick].toUpperCase(Locale.ROOT), false);
        }
        if (specExtra != null) {
            String sm = (sharpOpt != null) ? sharpOpt.values[sharpOpt.pick] : "off";
            boolean fxaaOn = fxaaOpt != null && "1".equals(fxaaOpt.values[fxaaOpt.pick]);
            String enhVal;
            if ("fsr".equals(sm)) enhVal = "FSR";
            else if ("cas".equals(sm) && fxaaOn) enhVal = "CAS+AA";
            else if ("cas".equals(sm)) enhVal = "CAS";
            else if (fxaaOn) enhVal = "FXAA";
            else enhVal = "OFF";
            specExtra.setValue(enhVal, !"OFF".equals(enhVal));
        }
    }

    private String buildVersionStamp() {
        try {
            String ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            if (ver != null && !ver.isEmpty()) return "v" + ver;
        } catch (Exception ignored) {
        }
        return "P5R";
    }

    private String cleanReadySummary() {
        String drv = activeDriver();
        String drvLabel = drv.isEmpty() ? t("Driver del sistema", "System Driver") : drv;
        return "Persona 5 Royal 1.0.0 · " + drvLabel;
    }

    private void updateReadiness() {
        String playLabel;
        String title;
        String body;
        boolean warn = false;

        if (!hasAccess()) {
            warn = true;
            title = t("PERMISO REQUERIDO", "ACCESS REQUIRED");
            body = t("Hace falta acceso a todos los archivos para leer los datos del juego.",
                    "All-files access is required to read your Persona 5 Royal data.");
            playLabel = t("DAR PERMISO", "GRANT ACCESS");
        } else if (gameUri() != null && !gameReadable()) {
            warn = true;
            title = t("ARCHIVO NO LEGIBLE", "GAME FILE UNREADABLE");
            body = Lang.f("No se puede leer %s. Vuelve a elegir el archivo.",
                    "Cannot read %s. Please select the file again.", gameName());
            playLabel = t("ELEGIR JUEGO", "CHOOSE GAME");
        } else if (gameUri() != null && !hasKeys()) {
            warn = true;
            title = t("FALTA PROD.KEYS", "MISSING PROD.KEYS");
            body = t("Elige el archivo prod.keys de tu consola en Archivos.",
                    "Select your console's prod.keys file to decrypt the game.");
            playLabel = t("ELEGIR KEYS", "CHOOSE KEYS");
        } else if (gameUri() != null && !prepared()) {
            warn = true;
            title = t("PREPARACIÓN PENDIENTE", "PREPARATION NEEDED");
            body = Lang.f("Juego: %s. Falta traducir los shaders una sola vez.",
                    "Game: %s. One-time shader translation is needed.", gameName());
            playLabel = t("PREPARAR", "PREPARE GAME");
        } else if (gameUri() != null) {
            title = t("LISTO PARA JUGAR", "READY TO PLAY");
            body = cleanReadySummary();
            playLabel = t("JUGAR  ▶", "PLAY  ▶");
        } else if (!hasData()) {
            warn = true;
            title = t("SIN DATOS DE JUEGO", "NO GAME DATA");
            body = t("Elige tu archivo .nsp / .xci (Persona 5 Royal 1.0.0) y prod.keys.",
                    "Choose your Persona 5 Royal 1.0.0 .nsp / .xci and prod.keys.");
            playLabel = t("ELEGIR JUEGO", "CHOOSE GAME");
        } else if (wrongBuild() != null) {
            warn = true;
            title = t("VERSIÓN NO COMPATIBLE", "UNSUPPORTED BUILD");
            body = Lang.f("exefs/main build %s no es P5R 1.0.0 base.",
                    "exefs/main build %s is not base P5R 1.0.0.", wrongBuild());
            playLabel = t("REINTENTAR", "CHECK AGAIN");
        } else {
            title = t("LISTO PARA JUGAR", "READY TO PLAY");
            body = cleanReadySummary();
            playLabel = t("JUGAR  ▶", "PLAY  ▶");
        }

        heroPlay.setText(playLabel);
        heroPlay.setWarningStyle(warn);
        pinnedPlay.setText(playLabel);
        pinnedPlay.setWarningStyle(warn);

        heroStatusCard.setHighlight(warn);
        heroStatusTitle.setTextColor(warn ? P5Theme.ACID_YELLOW : P5Theme.PAPER_WHITE);
        heroStatusTitle.setText(title);
        heroStatusBody.setText(body);

        railStatusText.setTextColor(warn ? P5Theme.ACID_YELLOW : P5Theme.MUTED_TEXT);
        railStatusText.setText(warn ? ("! " + title) : buildVersionStamp());

        if (gameSub != null) {
            gameSub.setText(gameUri() != null ? gameName()
                    : (hasData() ? t("Carpeta de datos: " + dataDir().getName(), "Data folder: " + dataDir().getName())
                    : t("Ningún archivo elegido", "No game file selected")));
        }
        if (keysSub != null) {
            keysSub.setText(hasKeys() ? t("prod.keys instalado", "prod.keys installed")
                    : t("prod.keys no encontrado", "prod.keys not installed"));
        }
    }

    // ---- Dialogs & Activity results ----

    void tell(String title, String message) {
        P5Views.showMessageDialog(this, title, message, "OK", null, null, null, null);
    }

    void askImport() {
        P5Views.showMessageDialog(this,
                t("Importar partidas", "Import Saves"),
                t("Elige un archivo ZIP o una carpeta con partidas de Persona 5 Royal (DATA01, DATA02... y SYSTEM). Se guarda copia de seguridad de tus partidas actuales.",
                        "Pick a ZIP file or folder with Persona 5 Royal saves (DATA01, DATA02... and SYSTEM). Your current saves are backed up first."),
                t("ZIP", "ZIP FILE"),
                new Runnable() {
                    @Override
                    public void run() {
                        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                        i.addCategory(Intent.CATEGORY_OPENABLE);
                        i.setType("*/*");
                        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[] {
                                "application/zip", "application/x-zip-compressed", "application/octet-stream"});
                        startActivityForResult(i, PICK_ZIP);
                    }
                },
                t("CARPETA", "FOLDER"),
                new Runnable() {
                    @Override
                    public void run() {
                        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), PICK_FOLDER);
                    }
                },
                t("CANCELAR", "CANCEL"));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PREPARE) {
            refresh();
            return;
        }
        if (result != RESULT_OK || data == null) return;
        if (request == PICK_KEYS) {
            onKeysPicked(data);
            refresh();
            return;
        }
        if (data.getData() == null) return;
        if (request == PICK_GAME) {
            onGamePicked(data.getData());
            refresh();
            return;
        }
        if (request == PICK_ICON) {
            onIconPicked(data.getData());
            return;
        }
        if (request == PICK_WALLPAPER) {
            onWallpaperPicked(data.getData());
            return;
        }
        if (request == PICK_DRIVER) {
            importDriver(data.getData());
            return;
        }
        if (request == PICK_CPK) {
            importCpk(data.getData());
            return;
        }
        try {
            SaveFiles.Result r = request == PICK_ZIP ? SaveFiles.importZip(getContentResolver(), data.getData())
                    : request == PICK_FOLDER ? SaveFiles.importTree(getContentResolver(), data.getData()) : null;
            if (r == null) {
                tell(t("No hay partidas", "No saves found"),
                        t("Ahí no encontré partidas de Persona 5 Royal: busca DATA01, DATA02... o SYSTEM.",
                                "No Persona 5 Royal saves found there: look for DATA01, DATA02... or SYSTEM."));
                return;
            }
            String slots = r.slots.isEmpty() ? t("solo ajustes del sistema", "system settings only")
                    : Lang.f("ranuras %s", "slots %s", android.text.TextUtils.join(", ", r.slots));
            String kept = r.backup == null ? "" : Lang.f("\n\nTus partidas anteriores quedaron en user/%s.",
                    "\n\nYour previous saves are in user/%s.", r.backup);
            tell(t("Partidas importadas", "Saves imported"),
                    Lang.f("%d archivos (%s). Ábrelas desde Cargar partida.", "%d files (%s). Open them from Load Game.",
                            r.files, slots) + kept);
        } catch (Exception e) {
            tell(t("No se pudo importar", "Import failed"), String.valueOf(e.getMessage()));
        }
    }

    void exportSaves() {
        try {
            File out = SaveFiles.export();
            if (out != null) {
                android.media.MediaScannerConnection.scanFile(this, new String[] {out.getPath()}, null, null);
            }
            if (out == null) {
                tell(t("No hay partidas", "No saves"), t("Todavía no hay partidas guardadas.", "There are no saves yet."));
            } else {
                tell(t("Partidas exportadas", "Saves exported"),
                        Lang.f("Guardadas en Descargas/%s. Se importan tal cual con el botón Importar.",
                                "Saved to Download/%s. Use Import to restore.", out.getName()));
            }
        } catch (Exception e) {
            tell(t("No se pudo exportar", "Export failed"), String.valueOf(e.getMessage()));
        }
    }

    void askAccess() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(new String[] {
                    android.Manifest.permission.READ_EXTERNAL_STORAGE,
                    android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 100);
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---- config.txt persistence (identical format to original Launcher) ----

    static Map<String, String> readConfig(File f) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!f.canRead()) return out;
        try (BufferedReader r = new BufferedReader(new FileReader(f))) {
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq > 0) out.put(line.substring(0, eq), line.substring(eq + 1));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static boolean writeConfig(File f, Map<String, String> config) {
        try (FileWriter w = new FileWriter(f)) {
            for (Map.Entry<String, String> e : config.entrySet()) w.write(e.getKey() + "=" + e.getValue() + "\n");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    void load() {
        config.clear();
        config.putAll(readConfig(new File(dataDir(), "config.txt")));
    }

    void save() {
        if (!hasAccess()) return;
        dataDir().mkdirs();
        if (!dataDir().isDirectory()) return;
        load();
        for (Option o : options) config.put(o.key, o.values[o.pick]);
        final String res = config.get("RESOLUTION");
        if ("360p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "360p");
            config.put("NX_RENDER_RES", "360p");
            config.put("NX_RES", "360p");
        } else if ("480p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "480p");
            config.put("NX_RENDER_RES", "480p");
            config.put("NX_RES", "480p");
        } else if ("540p".equals(res)) {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "540p");
            config.put("NX_RENDER_RES", "540p");
            config.put("NX_RES", "540p");
        } else if ("1080p".equals(res)) {
            config.put("NX_HANDHELD", "0");
            config.put("resolution", "1080p");
            config.put("NX_RENDER_RES", "1080p");
            config.put("NX_RES", "1080p");
        } else {
            config.put("NX_HANDHELD", "1");
            config.put("resolution", "720p");
            config.put("NX_RENDER_RES", "720p");
            config.put("NX_RES", "720p");
        }
        for (String k : new String[] {"RENDER_SCALE", "NX_ANISO_MAX", "NX_LOD_BIAS"}) config.remove(k);
        if ("auto".equals(config.get("NX_MAX_ANISO"))) config.remove("NX_MAX_ANISO");
        final String sharpMode = config.get("SHARPEN_MODE");
        config.put("NX_FSR", "fsr".equals(sharpMode) ? "1" : "0");
        config.put("NX_CAS", "cas".equals(sharpMode) ? "1" : "0");
        final String sharpStr = config.get("SHARPEN_STRENGTH");
        if ("subtle".equals(sharpStr)) {
            config.put("NX_CAS_STRENGTH", "0.42");
            config.put("NX_FSR_SHARP", "0.60");
        } else if ("strong".equals(sharpStr)) {
            config.put("NX_CAS_STRENGTH", "1.00");
            config.put("NX_FSR_SHARP", "0.00");
        } else {
            config.put("NX_CAS_STRENGTH", "0.72");
            config.put("NX_FSR_SHARP", "0.25");
        }
        if ("1".equals(config.get("NX_ULTRAWIDE"))) config.put("NX_STRETCH", "0");
        final String preset = config.get("GRAPHICS");
        if ("low".equals(preset)) config.put("NX_LOD_BIAS", "1");
        else if ("medium".equals(preset)) config.put("NX_LOD_BIAS", "0.5");
        else if ("sharp".equals(preset)) config.put("NX_LOD_BIAS", "-0.5");
        writeConfig(new File(dataDir(), "config.txt"), config);
    }

    int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    // ---- custom GPU driver ----

    File driverDir() {
        File d = new File(getFilesDir(), "gpu_driver");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    String activeDriver() {
        try (BufferedReader r = new BufferedReader(new FileReader(new File(driverDir(), "active.txt")))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            return "";
        }
    }

    void showDriver() {
        if (driverSub == null) return;
        String a = activeDriver();
        driverSub.setText(a.isEmpty() ? t("Driver del sistema", "System driver") : a);
    }

    void removeDriver() {
        File[] files = driverDir().listFiles();
        if (files != null) for (File f : files) f.delete();
        showDriver();
        updateReadiness();
        Toast.makeText(this, t("Driver del sistema activado", "System driver restored"), Toast.LENGTH_LONG).show();
    }

    static void copy(InputStream in, File dest) throws java.io.IOException {
        try (OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    void importDriver(Uri uri) {
        try {
            String picked = displayName(uri);
            File dir = driverDir();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
            String libName = null;
            if (picked.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                String soEntry = null, wanted = null;
                try (java.util.zip.ZipInputStream zin = new java.util.zip.ZipInputStream(getContentResolver().openInputStream(uri))) {
                    java.util.zip.ZipEntry e;
                    while ((e = zin.getNextEntry()) != null) {
                        String base = new File(e.getName()).getName();
                        if (e.isDirectory()) continue;
                        if (base.equals("meta.json")) {
                            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                            byte[] buf = new byte[8192];
                            int n;
                            while ((n = zin.read(buf)) > 0) bo.write(buf, 0, n);
                            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"libraryName\"\\s*:\\s*\"([^\"]+)\"")
                                    .matcher(bo.toString("UTF-8"));
                            if (m.find()) wanted = m.group(1);
                        } else if (base.endsWith(".so")) {
                            copy(zin, new File(dir, base));
                            if (soEntry == null) soEntry = base;
                        }
                    }
                }
                libName = wanted != null && new File(dir, wanted).exists() ? wanted : soEntry;
            } else {
                libName = picked.endsWith(".so") ? picked : picked + ".so";
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new java.io.IOException("Cannot open selected file");
                    copy(in, new File(dir, libName));
                }
            }
            if (libName == null) throw new java.io.IOException(t("No hay ningun .so dentro", "No .so file inside"));
            try (FileWriter w = new FileWriter(new File(dir, "active.txt"))) {
                w.write(libName + "\n");
            }
            showDriver();
            updateReadiness();
            tell(t("Driver importado", "Driver imported"),
                    Lang.f("%s\n\nCierra la app por completo y ábrela de nuevo.",
                            "%s\n\nClose the app completely and start it again.", libName));
        } catch (Exception e) {
            tell(t("No se pudo importar el driver", "Driver import failed"), String.valueOf(e.getMessage()));
        }
    }

    void importCpk(Uri uri) {
        try {
            File cpkDir = new File(dataDir(), "mods" + File.separator + "translation" + File.separator + "romfs" + File.separator + "CPK");
            if (!cpkDir.exists()) cpkDir.mkdirs();
            File dest = new File(cpkDir, "PATCH1.CPK");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(dest)) {
                if (in == null) throw new java.io.IOException("Cannot open selected file");
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            Toast.makeText(this, t("Traducción instalada en mods/translation/ (PATCH1.CPK)",
                    "Translation installed to mods/translation/ (PATCH1.CPK)"), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, t("Error al importar CPK: " + e.getMessage(),
                    "Error importing CPK: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void appendCappedFile(StringBuilder sb, String title, File f, int maxHeadLines, int maxTailLines) {
        if (!f.exists() || !f.isFile()) return;
        sb.append("\n=== ").append(title)
                .append(" (").append(f.length()).append(" bytes, modified ")
                .append(new java.util.Date(f.lastModified())).append(") ===\n");
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            java.util.ArrayList<String> head = new java.util.ArrayList<>(Math.min(maxHeadLines, 512));
            java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>(Math.min(maxTailLines, 2048));
            int total = 0;
            String line;
            while ((line = br.readLine()) != null) {
                total++;
                if (head.size() < maxHeadLines) {
                    head.add(line);
                } else {
                    if (tail.size() == maxTailLines) tail.removeFirst();
                    tail.addLast(line);
                }
            }
            for (String s : head) sb.append(s).append("\n");
            int skipped = total - head.size() - tail.size();
            if (skipped > 0) {
                sb.append("... [omitted ").append(skipped).append(" middle lines] ...\n");
            }
            for (String s : tail) sb.append(s).append("\n");
        } catch (Exception e) {
            sb.append("read error: ").append(e.getMessage()).append("\n");
        }
    }

    void exportLogs() {
        try {
            File dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (!dl.exists()) dl.mkdirs();
            File out = new File(dl, "p5r-log.txt");
            StringBuilder sb = new StringBuilder();
            sb.append("=== Persona 5 Royal Recomp Log Export ===\n");
            try {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
                sb.append("App: ").append(pi.versionName).append(" (versionCode ").append(vc).append(")\n");
            } catch (Exception ignored) {}
            String soc = Build.HARDWARE + " / " + Build.BOARD;
            if (Build.VERSION.SDK_INT >= 31 && Build.SOC_MODEL != null && !Build.SOC_MODEL.isEmpty()) {
                soc = Build.SOC_MODEL + " (" + soc + ")";
            }
            sb.append("Device: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
            sb.append("SoC / Board: ").append(soc).append("\n");
            sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
                if (am != null) {
                    android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                    am.getMemoryInfo(mi);
                    sb.append("Memory: ").append(mi.availMem / (1024L * 1024L)).append(" MB free / ")
                            .append(mi.totalMem / (1024L * 1024L)).append(" MB total\n");
                }
            } catch (Exception ignored) {}
            String drv = activeDriver();
            sb.append("GPU Driver: ").append(drv.isEmpty() ? "System driver" : drv).append("\n");
            sb.append("Date: ").append(new java.util.Date().toString()).append("\n");

            sb.append("\n=== Active Settings ===\n");
            for (Option o : options) {
                String val = (o.pick >= 0 && o.pick < o.values.length) ? o.values[o.pick] : "?";
                String lbl = (o.pick >= 0 && o.pick < o.labelsEn.length) ? o.labelsEn[o.pick] : "?";
                sb.append(o.key).append("=").append(val).append(" (").append(lbl).append(")\n");
            }

            sb.append("\n=== Mods ===\n");
            File modsDir = new File(dataDir(), "mods");
            File[] modEntries = modsDir.isDirectory() ? modsDir.listFiles() : null;
            if (modEntries != null && modEntries.length > 0) {
                java.util.Arrays.sort(modEntries);
                for (File m : modEntries) {
                    sb.append("- ").append(m.getName()).append(m.isDirectory() ? "/" : "").append("\n");
                }
            } else {
                sb.append("(none)\n");
            }

            appendCappedFile(sb, "nx_crash.log", new File(dataDir(), "nx_crash.log"), 300, 300);
            appendCappedFile(sb, "nx.log", new File(dataDir(), "nx.log"), 120, 500);
            appendCappedFile(sb, "nx.log.prev", new File(dataDir(), "nx.log.prev"), 80, 300);

            sb.append("\n=== logcat (filtered, last 400 lines) ===\n");
            try {
                Process p = Runtime.getRuntime().exec(new String[] {
                        "logcat", "-d", "-t", "400", "-s",
                        "p5r:V", "AndroidRuntime:E", "DEBUG:V", "libc:F", "vulkan:W", "*:E"
                });
                try (BufferedReader br = new BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line).append("\n");
                }
            } catch (Exception e) {
                sb.append("logcat capture error: ").append(e.getMessage()).append("\n");
            }

            try (FileWriter fw = new FileWriter(out)) {
                fw.write(sb.toString());
            }

            Toast.makeText(this, t("Registros exportados a Download/p5r-log.txt",
                    "Logs exported to Download/p5r-log.txt"), Toast.LENGTH_SHORT).show();

            Uri logUri = Uri.parse("content://" + getPackageName() + ".logprovider" + out.getAbsolutePath());
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_STREAM, logUri);
            share.putExtra(Intent.EXTRA_SUBJECT, "Persona 5 Royal Debug Log");
            share.setClipData(ClipData.newRawUri("Log", logUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, t("Compartir archivo de registros", "Share log file")));
        } catch (Exception e) {
            Toast.makeText(this, t("Error al exportar registros: " + e.getMessage(),
                    "Error exporting logs: " + e.getMessage()), Toast.LENGTH_LONG).show();
        }
    }
}
