package com.fileswitch.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.method.PasswordTransformationMethod;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.*;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.rendering.ImageType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 1, SAVE_FILE = 2, PICK_PDF_TOOL = 3, PICK_ZIP = 4, PICK_CREATE_ZIP = 5, PICK_IMAGE_FOR_PDF = 6, REQ_NOTIF = 7, PICK_EXIF_IMAGE = 8, PICK_CONTACT_SHEET = 9, PICK_REPAIR_DOC = 10, PICK_TOOL_FILE = 20, PICK_TOOL_SECONDARY_FILE = 21;
    private static final long MAX_FILE = 100L * 1024 * 1024, MAX_PIXELS = 20_000_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<InputFile> files = new ArrayList<>();
    private ConversionNotificationManager notifManager;
    private LinearLayout root, body, fileRows, resultRows;
    private TextView progressText, convertButton;
    private Spinner formatPicker;
    private Spinner imageOperationPicker;
    private int imageResizePercent = 75;
    private int imageQuality = 90;
    private int outputDpi = 96;
    private String page = "Convert", themeChoice = "System";
    private ScrollView currentScrollView;
    private final Map<String, Integer> pageScrollPositions = new HashMap<>();
    private String preferredTarget;
    private String pendingPdfTool;
    private ArrayList<Uri> pendingPdfImageSources;
    private boolean busy, cancelBatch;
    private boolean retryFailedOnly;
    private boolean splashShown;
    private EditText outputNameInput;
    private LinearLayout passwordProtectionContainer;
    private EditText passwordInput;
    private CheckBox protectCheckbox;
    private OutputFile batchArchive;
    private boolean isImportingAnimation = false;
    private int justImportedCount = 0;
    private FileCreationAnimationView convertAnimView;

    // Dedicated Full-Screen Tool Page State
    private String activeToolId = null;
    private final ArrayList<Uri> toolSources = new ArrayList<>();
    private final ArrayList<OutputFile> toolResultFiles = new ArrayList<>();
    private boolean toolProcessing = false;
    private String toolStatusText = "";
    private Uri toolSecondaryUri = null;
    private Bitmap toolSecondaryBitmap = null;
    private int toolPdfPageCount = 0;
    private boolean toolPdfCounting = false;

    // Tool Option Settings State
    private int toolCompressLevel = 70;
    private int toolResizePercent = 75;
    private int toolResizePreset = -1;
    private String toolPassword = "";
    private boolean toolAllowPrint = true, toolAllowCopy = true, toolAllowEdit = true;
    private String toolWatermarkText = "";
    private String toolStampText = "CONFIDENTIAL";
    private int toolStampPos = 0;
    private String toolFormValues = "";
    private boolean toolFlattenForm = true;
    private String toolSignerName = "";
    private int toolSignPage = 0;
    private String toolMetaTitle = "", toolMetaAuthor = "", toolMetaSubject = "", toolMetaKeywords = "";
    private String toolCropMargin = "12";
    private String toolPageRange = "";
    private final HashSet<Integer> toolSelectedPages = new HashSet<>();
    private final ArrayList<Integer> toolReorderedPages = new ArrayList<>();
    private int toolRotateAngle = 90;
    private String toolCustomOutputName = "";
    private String toolExifDesc = "", toolExifArtist = "", toolExifCopyright = "", toolExifMake = "", toolExifModel = "";
    private boolean toolExifScrubGps = false;
    private int toolContactSheetCols = 3;
    private String toolImageTarget = "jpg";
    private int toolImageQuality = 90;
    private int toolImageDpi = 96;
    private String toolDocTarget = "pdf";
    private String toolOcrLang = "latin";
    private String toolOcrTarget = "txt";

    private final Map<Uri, Long> uriSizeCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Uri, String> uriNameCache = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Uri, String> uriTypeCache = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class InputFile {
        Uri uri;
        String name, type, state = "Ready", details = "";
        long size = -1;
        Bitmap preview;
        ImageView previewView;
        boolean previewLoading;
        OutputFile output;
        final ArrayList<OutputFile> extraOutputs = new ArrayList<>();

        InputFile(Uri uri, String name, String type) {
            this.uri = uri;
            this.name = name;
            this.type = type;
        }

        InputFile(Uri uri, String name, String type, long size) {
            this.uri = uri;
            this.name = name;
            this.type = type;
            this.size = size;
        }
    }

    private static final class OutputFile {
        File file;
        String name, mime, target;
        long sourceSize;

        OutputFile(File file, String name, String mime, String target) {
            this.file = file;
            this.name = name;
            this.mime = mime;
            this.target = target;
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        notifManager = new ConversionNotificationManager(this);
        SharedPreferences prefs = getPreferences(0);
        themeChoice = prefs.getString("theme", "System");
        imageQuality = prefs.getInt("default_quality", 90);
        outputDpi = prefs.getInt("default_dpi", 96);
        worker.execute(() -> PdfTools.init(this));
        pruneCache();
        checkNotificationPermission();

        String action = getIntent().getAction();
        if (Intent.ACTION_SEND.equals(action) || Intent.ACTION_SEND_MULTIPLE.equals(action)) {
            ArrayList<Uri> incoming = new ArrayList<>();
            if (Intent.ACTION_SEND_MULTIPLE.equals(action) && getIntent().getClipData() != null) {
                ClipData clip = getIntent().getClipData();
                for (int i = 0; i < clip.getItemCount(); i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null) incoming.add(u);
                }
            } else {
                Uri u = getIntent().getParcelableExtra(Intent.EXTRA_STREAM);
                if (u != null) incoming.add(u);
            }
            if (!incoming.isEmpty()) {
                if (prefs.getBoolean("consent_accepted", false) && prefs.getBoolean("storage_consent_accepted", false)) {
                    showPage("Convert");
                    addPickedFiles(incoming);
                    return;
                }
            }
        }

        if (!splashShown && state == null) {
            showSplash();
        } else {
            boolean consentAccepted = prefs.getBoolean("consent_accepted", false);
            boolean storageConsentAccepted = prefs.getBoolean("storage_consent_accepted", false);
            if (!consentAccepted || !storageConsentAccepted) {
                showSplashOrConsent();
            } else {
                showPage("Convert");
            }
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if ("System".equals(themeChoice) || "System Default".equals(themeChoice)) {
            setPalette();
            if (page != null) {
                showPage(page);
            }
        }
    }

    private void showSplash() {
        splashShown = true;
        setPalette();
        boolean dark = isDark();
        int splashBg = dark ? bg : Color.parseColor("#245CCB");
        int splashInk = dark ? ink : Color.WHITE;
        int splashMuted = dark ? muted : Color.parseColor("#D7E2F4");
        getWindow().setStatusBarColor(splashBg);
        getWindow().setNavigationBarColor(splashBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }
        LinearLayout splashRoot = column();
        splashRoot.setBackgroundColor(splashBg);
        splashRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        setContentView(splashRoot);

        LinearLayout centerBox = column();
        centerBox.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.fileswitch_splash_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        centerBox.addView(logo, lp(160, 160));

        TextView appTitle = text("FileSwitch", 32, true, splashInk);
        LinearLayout.LayoutParams tp = lp(-2, -2);
        tp.topMargin = dp(18);
        centerBox.addView(appTitle, tp);

        splashRoot.addView(centerBox, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = column();
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        pad(footer, 0, 0, 0, 40);

        TextView fromText = text("by", 13, false, splashMuted);
        fromText.setGravity(Gravity.CENTER);
        fromText.setLetterSpacing(0.08f);
        footer.addView(fromText);

        TextView authorText = text("Sankalp", 17, true, splashInk);
        authorText.setGravity(Gravity.CENTER);
        authorText.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams ap = lp(-2, -2);
        ap.topMargin = dp(2);
        footer.addView(authorText, ap);

        splashRoot.addView(footer, lp(-1, -2));

        splashRoot.setAlpha(0f);
        splashRoot.animate().alpha(1f).setDuration(300).start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            boolean consentAccepted = getPreferences(0).getBoolean("consent_accepted", false);
            boolean storageConsentAccepted = getPreferences(0).getBoolean("storage_consent_accepted", false);
            if (!consentAccepted || !storageConsentAccepted) {
                page = "Consent";
                showUnifiedConsentPopup(false);
            } else {
                splashRoot.animate().alpha(0f).setDuration(220).withEndAction(() -> {
                    showPage("Convert");
                }).start();
            }
        }, 1100);
    }

    private void showShortLoading(Runnable onComplete) {
        setPalette();
        boolean dark = isDark();
        int splashBg = dark ? bg : Color.parseColor("#245CCB");
        int splashInk = dark ? ink : Color.WHITE;
        getWindow().setStatusBarColor(splashBg);
        getWindow().setNavigationBarColor(splashBg);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            getWindow().getDecorView().setSystemUiVisibility(0);
        }

        LinearLayout splashRoot = column();
        splashRoot.setBackgroundColor(splashBg);
        splashRoot.setGravity(Gravity.CENTER);
        setContentView(splashRoot);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.fileswitch_splash_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        splashRoot.addView(logo, lp(120, 120));

        TextView appTitle = text("FileSwitch", 26, true, splashInk);
        LinearLayout.LayoutParams tp = lp(-2, -2);
        tp.topMargin = dp(14);
        splashRoot.addView(appTitle, tp);

        ProgressBar pb = new ProgressBar(this);
        pb.setIndeterminate(true);
        if (Build.VERSION.SDK_INT >= 21) {
            pb.getIndeterminateDrawable().setColorFilter(splashInk, android.graphics.PorterDuff.Mode.SRC_IN);
        }
        LinearLayout.LayoutParams pbp = lp(36, 36);
        pbp.topMargin = dp(20);
        splashRoot.addView(pb, pbp);

        splashRoot.setAlpha(0f);
        splashRoot.animate().alpha(1f).setDuration(200).start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            splashRoot.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                if (onComplete != null) onComplete.run();
            }).start();
        }, 650);
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private int bg, surface, ink, muted, accent, line;
    private long lastGlobalClickTime = 0;

    private boolean isClickDebounced() {
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastGlobalClickTime < 350) {
            return true;
        }
        lastGlobalClickTime = now;
        return false;
    }

    private boolean isDark() {
        if ("Dark".equals(themeChoice)) return true;
        if ("Light".equals(themeChoice)) return false;
        return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private AlertDialog.Builder dialogBuilder() {
        int themeRes = isDark() ? android.R.style.Theme_Material_Dialog_Alert : android.R.style.Theme_Material_Light_Dialog_Alert;
        return new AlertDialog.Builder(this, themeRes);
    }

    private void setPalette() {
        boolean dark = isDark();
        bg = Color.parseColor(dark ? "#15191F" : "#F5F4F0");
        surface = Color.parseColor(dark ? "#20262E" : "#FFFFFF");
        ink = Color.parseColor(dark ? "#F3F3F0" : "#202329");
        muted = Color.parseColor(dark ? "#A9B0BB" : "#707681");
        accent = Color.parseColor(dark ? "#8EB6FF" : "#1857D5");
        line = Color.parseColor(dark ? "#343C47" : "#E8E7E2");
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(bg));
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(surface);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = 0;
            if (!dark) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                }
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }

    private int dp(float n) {
        return (int) (n * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int radius, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        if (stroke != 0) d.setStroke(dp(1), stroke);
        return d;
    }

    private TextView text(String value, int size, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private LinearLayout column() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        return v;
    }

    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this);
        v.setGravity(Gravity.CENTER_VERTICAL);
        return v;
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w < 0 ? w : dp(w), h < 0 ? h : dp(h));
    }

    private void pad(View v, int a, int b, int c, int d) {
        v.setPadding(dp(a), dp(b), dp(c), dp(d));
    }

    private <T> ArrayAdapter<T> createThemedAdapter(T[] items) {
        return new ArrayAdapter<T>(this, android.R.layout.simple_spinner_item, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getView(position, convertView, parent);
                view.setTextColor(ink);
                view.setTextSize(14);
                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                TextView view = (TextView) super.getDropDownView(position, convertView, parent);
                view.setTextColor(ink);
                view.setBackgroundColor(surface);
                view.setPadding(dp(16), dp(12), dp(16), dp(12));
                return view;
            }
        };
    }

    private void styleEditText(EditText edit) {
        edit.setTextColor(ink);
        edit.setHintTextColor(muted);
        edit.setBackground(shape(surface, 14, line));
    }

    private void makeCircle(View v) {
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        v.setClipToOutline(true);
    }

    private String getSettingsPageTitle(String p) {
        switch (p) {
            case "Settings_PrivacyPolicy": return "Privacy Policy";
            case "Settings_Terms": return "Terms of Use";
            case "Help":
            case "Settings_HelpSupport": return "Help & Support";
            case "Settings_Help_GettingStarted": return "Getting Started";
            case "Settings_Help_Converting": return "Converting Files";
            case "Settings_Help_Storage": return "Where Files Saved";
            case "Settings_Help_Filenames": return "Custom Filenames";
            case "Settings_Help_PdfTools": return "PDF Tools Guide";
            case "Settings_Help_Troubleshooting": return "Troubleshooting";
            case "Settings_Help_Assistance": return "Need Assistance?";
            case "Settings_ReportProblem": return "Report a Problem";
            case "Settings_About": return "About FileSwitch";
            case "Settings_Licenses": return "Open Source Licenses";
            case "Settings_Copyright": return "Copyright";
            case "Settings_Copyright_Notice": return "Copyright Notice";
            case "Settings_StorageInfo": return "Storage & Output";
            case "Settings_Storage_Folder": return "FileSwitch Folder";
            case "Settings_Storage_DuplicateSafety": return "Duplicate Safety";
            case "Settings_Storage_CacheManagement": return "Cache Management";
            default: return "Settings";
        }
    }

    private void showPage(String next) {
        if (currentScrollView != null && page != null) {
            pageScrollPositions.put(page, currentScrollView.getScrollY());
        }
        page = next;
        setPalette();
        root = column();
        root.setBackgroundColor(bg);
        setContentView(root);

        LinearLayout top = row();
        pad(top, 20, 16, 20, 14);

        boolean isConsent = "Consent".equals(page);
        if ("Convert".equals(page) || isConsent) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.fileswitch_icon);
            icon.setContentDescription("FileSwitch home");
            icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
            icon.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(9));
                }
            });
            icon.setClipToOutline(true);
            top.addView(icon, lp(38, 38));
            LinearLayout title = column();
            TextView brand = text("FileSwitch", 20, true, ink);
            brand.setSingleLine(true);
            brand.setEllipsize(null);
            title.addView(brand, lp(-2, -2));
            TextView subTag = text(isConsent ? "TERMS & PRIVACY" : "FILE UTILITY", 10, true, muted);
            subTag.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams stp = lp(-2, -2);
            stp.topMargin = dp(2);
            title.addView(subTag, stp);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = dp(12);
            top.addView(title, tp);
        } else {
            View back = new View(this) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                private final Path arrowPath = new Path();

                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    paint.setColor(ink);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2.2f));
                    paint.setStrokeCap(Paint.Cap.ROUND);
                    paint.setStrokeJoin(Paint.Join.ROUND);

                    float cx = getWidth() / 2f;
                    float cy = getHeight() / 2f;
                    float shaftLen = dp(6.5f);
                    float wingLen = dp(5.5f);

                    arrowPath.reset();
                    // Shaft:
                    arrowPath.moveTo(cx + shaftLen, cy);
                    arrowPath.lineTo(cx - shaftLen, cy);
                    // Top wing:
                    arrowPath.moveTo(cx - shaftLen + wingLen, cy - wingLen);
                    arrowPath.lineTo(cx - shaftLen, cy);
                    // Bottom wing:
                    arrowPath.lineTo(cx - shaftLen + wingLen, cy + wingLen);

                    canvas.drawPath(arrowPath, paint);
                }
            };
            back.setContentDescription("Navigate back");
            back.setBackground(shape(surface, 12, line));
            back.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).start();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                return false;
            });
            back.setOnClickListener(v -> {
                if (isClickDebounced()) return;
                handleBackNavigation();
            });
            top.addView(back, lp(40, 40));

            if ("ToolPage".equals(page)) {
                LinearLayout titleCol = column();
                TextView toolTitleView = text(getToolTitle(activeToolId), 17, true, ink);
                toolTitleView.setSingleLine(true);
                toolTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                titleCol.addView(toolTitleView, lp(-2, -2));
                TextView catView = text(getToolCategory(activeToolId), 10, true, accent);
                catView.setLetterSpacing(0.1f);
                LinearLayout.LayoutParams cp = lp(-2, -2);
                cp.topMargin = dp(2);
                titleCol.addView(catView, cp);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
                tp.leftMargin = dp(12);
                top.addView(titleCol, tp);
            } else if (page.startsWith("Settings_Help_")) {
                LinearLayout titleCol = column();
                TextView pageTitleView = text(getSettingsPageTitle(page), 17, true, ink);
                pageTitleView.setSingleLine(true);
                pageTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                titleCol.addView(pageTitleView, lp(-2, -2));
                TextView catView = text("HELP & SUPPORT", 10, true, accent);
                catView.setLetterSpacing(0.1f);
                LinearLayout.LayoutParams cp = lp(-2, -2);
                cp.topMargin = dp(2);
                titleCol.addView(catView, cp);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
                tp.leftMargin = dp(12);
                top.addView(titleCol, tp);
            } else if (page.startsWith("Settings_Copyright_")) {
                LinearLayout titleCol = column();
                TextView pageTitleView = text(getSettingsPageTitle(page), 17, true, ink);
                pageTitleView.setSingleLine(true);
                pageTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                titleCol.addView(pageTitleView, lp(-2, -2));
                TextView catView = text("COPYRIGHT", 10, true, accent);
                catView.setLetterSpacing(0.1f);
                LinearLayout.LayoutParams cp = lp(-2, -2);
                cp.topMargin = dp(2);
                titleCol.addView(catView, cp);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
                tp.leftMargin = dp(12);
                top.addView(titleCol, tp);
            } else if (page.startsWith("Settings_Storage_")) {
                LinearLayout titleCol = column();
                TextView pageTitleView = text(getSettingsPageTitle(page), 17, true, ink);
                pageTitleView.setSingleLine(true);
                pageTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                titleCol.addView(pageTitleView, lp(-2, -2));
                TextView catView = text("STORAGE & OUTPUT", 10, true, accent);
                catView.setLetterSpacing(0.1f);
                LinearLayout.LayoutParams cp = lp(-2, -2);
                cp.topMargin = dp(2);
                titleCol.addView(catView, cp);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
                tp.leftMargin = dp(12);
                top.addView(titleCol, tp);
            } else if (page.startsWith("Settings_") || "Help".equals(page)) {
                LinearLayout titleCol = column();
                TextView pageTitleView = text(getSettingsPageTitle(page), 17, true, ink);
                pageTitleView.setSingleLine(true);
                pageTitleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
                titleCol.addView(pageTitleView, lp(-2, -2));
                TextView catView = text("SETTINGS", 10, true, accent);
                catView.setLetterSpacing(0.1f);
                LinearLayout.LayoutParams cp = lp(-2, -2);
                cp.topMargin = dp(2);
                titleCol.addView(catView, cp);
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
                tp.leftMargin = dp(12);
                top.addView(titleCol, tp);
            } else {
                View spacer = new View(this);
                top.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));
            }
        }

        boolean consentAccepted = getPreferences(0).getBoolean("consent_accepted", false);
        if (consentAccepted && !isConsent) {
            View menu = new View(this) {
                private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    paint.setColor(ink);
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(dp(2f));
                    paint.setStrokeCap(Paint.Cap.ROUND);

                    float cx = getWidth() / 2f;
                    float cy = getHeight() / 2f;
                    float halfW = dp(7f);
                    float gap = dp(4.5f);

                    canvas.drawLine(cx - halfW, cy - gap, cx + halfW, cy - gap, paint);
                    canvas.drawLine(cx - halfW, cy, cx + halfW, cy, paint);
                    canvas.drawLine(cx - halfW, cy + gap, cx + halfW, cy + gap, paint);
                }
            };
            menu.setContentDescription("Open navigation menu");
            menu.setBackground(shape(surface, 12, line));
            menu.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).start();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                return false;
            });
            menu.setOnClickListener(v -> {
                if (isClickDebounced()) return;
                showNavigationMenu(v);
            });
            top.addView(menu, lp(40, 40));
        } else {
            View spacer = new View(this);
            top.addView(spacer, lp(40, 40));
        }

        root.addView(top, lp(-1, -2));

        FrameLayout contentContainer = new FrameLayout(this);

        if ("Settings_PrivacyPolicy".equals(page) || "Settings_Terms".equals(page)) {
            ImageView watermark = new ImageView(this);
            watermark.setImageResource(R.drawable.fileswitch_icon);
            watermark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            watermark.setScaleType(ImageView.ScaleType.FIT_CENTER);
            watermark.setAlpha(isDark() ? 0.04f : 0.06f);
            FrameLayout.LayoutParams wlp = new FrameLayout.LayoutParams(dp(240), dp(240));
            wlp.gravity = Gravity.CENTER;
            contentContainer.addView(watermark, wlp);
        }

        ScrollView scroll = new ScrollView(this);
        currentScrollView = scroll;
        scroll.setFillViewport(true);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        body = column();
        pad(body, 20, 10, 20, 24);
        scroll.addView(body);
        contentContainer.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        root.addView(contentContainer, new LinearLayout.LayoutParams(-1, 0, 1));

        if (Build.VERSION.SDK_INT >= 23) {
            scroll.setOnScrollChangeListener((v, scrollX, scrollY, oldScrollX, oldScrollY) -> {
                if (page != null) {
                    pageScrollPositions.put(page, scrollY);
                }
            });
        } else {
            scroll.getViewTreeObserver().addOnScrollChangedListener(() -> {
                if (page != null) {
                    pageScrollPositions.put(page, scroll.getScrollY());
                }
            });
        }
        switch (next) {
            case "Consent":
                showSplashOrConsent();
                return;
            case "Convert":
                renderConvert();
                break;
            case "Tools":
                renderTools();
                break;
            case "ToolPage":
                renderToolPage();
                break;
            case "History":
                renderHistory();
                break;
            case "Settings_PrivacyPolicy":
            case "Settings_DataPrivacy":
            case "Settings_Permissions":
                renderSettingsPrivacyPolicy();
                break;
            case "Settings_Terms":
                renderSettingsTerms();
                break;
            case "Help":
            case "Settings_HelpSupport":
                renderSettingsHelpSupport();
                break;
            case "Settings_Help_GettingStarted":
                renderHelpGettingStarted();
                break;
            case "Settings_Help_Converting":
                renderHelpConverting();
                break;
            case "Settings_Help_Storage":
                renderHelpStorage();
                break;
            case "Settings_Help_Filenames":
                renderHelpFilenames();
                break;
            case "Settings_Help_PdfTools":
                renderHelpPdfTools();
                break;
            case "Settings_Help_Troubleshooting":
                renderHelpTroubleshooting();
                break;
            case "Settings_Help_Assistance":
                renderHelpAssistance();
                break;
            case "Settings_ReportProblem":
                renderSettingsReportProblem();
                break;
            case "Settings_About":
                renderSettingsAbout();
                break;
            case "Settings_Licenses":
                renderSettingsLicenses();
                break;
            case "Settings_Copyright":
                renderSettingsCopyright();
                break;
            case "Settings_Copyright_Notice":
                renderCopyrightNotice();
                break;
            case "Settings_StorageInfo":
                renderSettingsStorageInfo();
                break;
            case "Settings_Storage_Folder":
                renderStorageFolder();
                break;
            case "Settings_Storage_DuplicateSafety":
                renderStorageDuplicateSafety();
                break;
            case "Settings_Storage_CacheManagement":
                renderStorageCacheManagement();
                break;
            case "Settings":
            default:
                renderSettings();
                break;
        }
        Integer savedY = pageScrollPositions.get(next);
        if (savedY != null && savedY > 0) {
            final int targetY = savedY;
            scroll.post(() -> scroll.scrollTo(0, targetY));
            scroll.postDelayed(() -> scroll.scrollTo(0, targetY), 40);
        }
        body.setAlpha(0f);
        body.animate().alpha(1f).setDuration(160).setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        root.setOnDragListener((view, event) -> {
            switch (event.getAction()) {
                case android.view.DragEvent.ACTION_DRAG_STARTED:
                    return event.getClipDescription() != null && event.getClipDescription().hasMimeType("image/*");
                case android.view.DragEvent.ACTION_DROP:
                    try {
                        requestDragAndDropPermissions(event);
                        ArrayList<Uri> dropped = new ArrayList<>();
                        ClipData clip = event.getClipData();
                        for (int i = 0; i < clip.getItemCount(); i++)
                            if (clip.getItemAt(i).getUri() != null) dropped.add(clip.getItemAt(i).getUri());
                        addPickedFiles(dropped);
                        return true;
                    } catch (Exception ignored) {
                        return false;
                    }
                default:
                    return true;
            }
        });
    }

    private void heading(String title, String subtitle) {
        heading(title, subtitle, false);
    }

    private void heading(String title, String subtitle, boolean animate) {
        TextView t = text(title, 26, true, ink);
        body.addView(t);
        TextView sub = text(subtitle, 14, false, muted);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(6);
        p.bottomMargin = dp(18);
        body.addView(sub, p);

        if (animate) {
            t.setAlpha(0f);
            t.setTranslationY(dp(-8));
            t.setScaleX(0.96f);
            t.setScaleY(0.96f);
            t.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(420)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                    .start();

            sub.setAlpha(0f);
            sub.setTranslationY(dp(-4));
            sub.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setStartDelay(80)
                    .setDuration(350)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();
        }
    }

    private void card(LinearLayout parent, View inside) {
        LinearLayout box = column();
        pad(box, 16, 16, 16, 16);
        box.setBackground(shape(surface, 20, line));
        box.addView(inside);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.bottomMargin = dp(14);
        parent.addView(box, p);
    }

    private TextView button(String label, boolean primary, Runnable action) {
        TextView b = text(label, 15, true, primary ? Color.WHITE : ink);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(52));
        b.setBackground(shape(primary ? Color.parseColor("#245CCB") : surface, 16, primary ? 0 : line));
        b.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            return false;
        });
        b.setOnClickListener(v -> {
            if (isClickDebounced()) return;
            if (action != null) action.run();
        });
        return b;
    }

    private void renderConvert() {
        heading("Convert a file", "Choose an image, PDF, or document and convert it.", true);
        if (files.isEmpty()) {
            LinearLayout hero = column();
            pad(hero, 22, 22, 22, 22);
            hero.setBackground(shape(surface, 24, line));
            LinearLayout brandline = row();
            TextView eyebrow = text("UNIVERSAL CONVERTER", 11, true, accent);
            eyebrow.setLetterSpacing(.12f);
            brandline.addView(eyebrow);
            TextView tag = text("LOCAL & SECURE", 10, true, muted);
            tag.setGravity(Gravity.CENTER);
            tag.setBackground(shape(bg, 14, 0));
            pad(tag, 10, 4, 10, 4);
            LinearLayout.LayoutParams tagp = lp(-2, 26);
            tagp.leftMargin = dp(10);
            brandline.addView(tag, tagp);
            hero.addView(brandline);

            TextView title = text("What would you\nlike to convert?", 27, true, ink);
            LinearLayout.LayoutParams m = lp(-1, -2);
            m.topMargin = dp(16);
            hero.addView(title, m);

            TextView supported = text("JPG   PNG   WEBP   PDF   DOCX   XLSX   OCR", 12, true, muted);
            supported.setLetterSpacing(.08f);
            m = lp(-1, -2);
            m.topMargin = dp(10);
            hero.addView(supported, m);

            TextView pick = button("Select files", true, this::openPicker);
            pick.setTextSize(16);
            LinearLayout.LayoutParams bp = lp(-1, 56);
            bp.topMargin = dp(22);
            hero.addView(pick, bp);

            TextView local = text("Browse device storage, Downloads or Google Drive", 12, false, muted);
            local.setGravity(Gravity.CENTER);
            m = lp(-1, -2);
            m.topMargin = dp(14);
            hero.addView(local, m);
            body.addView(hero, lp(-1, -2));

            LinearLayout shortcuts = row();
            String[][] items = {
                {"IMAGE", "Image tools", "Convert & edit"},
                {"PDF", "Images to PDF", "Combine to PDF"},
                {"DOCS", "Document tools", "DOCX, XLSX, TXT"}
            };
            for (String[] item : items) {
                LinearLayout tile = column();
                pad(tile, 14, 13, 14, 13);
                tile.setBackground(shape(surface, 18, line));
                TextView glyph = text(item[0], 11, true, accent);
                glyph.setLetterSpacing(0.08f);
                tile.addView(glyph);
                TextView name = text(item[1], 13, true, ink);
                name.setMaxLines(1);
                name.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams np = lp(-1, -2);
                np.topMargin = dp(5);
                tile.addView(name, np);
                TextView desc = text(item[2], 11, false, muted);
                desc.setMaxLines(1);
                desc.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams dp = lp(-1, -2);
                dp.topMargin = dp(2);
                tile.addView(desc, dp);

                tile.setOnTouchListener((v, event) -> {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                        v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
                    else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                        v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                    return false;
                });
                tile.setOnClickListener(v -> {
                    if (isClickDebounced()) return;
                    if ("IMAGE".equals(item[0])) {
                        preferredTarget = null;
                        openPicker();
                    } else if ("PDF".equals(item[0])) {
                        preferredTarget = "pdf";
                        openPicker();
                    } else {
                        preferredTarget = "docx";
                        openPicker();
                    }
                });
                LinearLayout.LayoutParams xp = new LinearLayout.LayoutParams(0, dp(86), 1);
                if (shortcuts.getChildCount() > 0) xp.leftMargin = dp(8);
                shortcuts.addView(tile, xp);
            }
            LinearLayout.LayoutParams shp = lp(-1, 86);
            shp.topMargin = dp(14);
            body.addView(shortcuts, shp);

            LinearLayout featureCard = column();
            pad(featureCard, 16, 16, 16, 16);
            featureCard.setBackground(shape(surface, 20, line));
            LinearLayout featureHeader = row();
            TextView featureIcon = text("🔒", 14, false, ink);
            featureHeader.addView(featureIcon);
            TextView featureTitle = text("  100% Offline & Private", 14, true, ink);
            featureHeader.addView(featureTitle);
            featureCard.addView(featureHeader);
            TextView featureDesc = text("All conversions run locally on your device hardware with zero data uploads, keeping your documents completely private.", 12, false, muted);
            featureDesc.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams fdp = lp(-1, -2);
            fdp.topMargin = dp(8);
            featureCard.addView(featureDesc, fdp);
            LinearLayout.LayoutParams fcp = lp(-1, -2);
            fcp.topMargin = dp(14);
            body.addView(featureCard, fcp);

            addRecentShortcuts();
        } else {
            if (isImportingAnimation) {
                LinearLayout importCard = column();
                pad(importCard, 10, 8, 10, 8);
                importCard.setBackground(shape(surface, 16, line));
                FileImportAnimationView importAnim = new FileImportAnimationView(this);
                importAnim.setPalette(surface, ink, muted, accent, line);
                importCard.addView(importAnim, lp(-1, 56));
                LinearLayout.LayoutParams icp = lp(-1, -2);
                icp.bottomMargin = dp(10);
                body.addView(importCard, icp);
                importAnim.startImport(justImportedCount, () -> {
                    isImportingAnimation = false;
                    importCard.animate().alpha(0f).setDuration(200).withEndAction(() -> importCard.setVisibility(View.GONE)).start();
                });
            }

            TextView sub = text(files.size() + " selected", 15, true, ink);
            body.addView(sub);
            LinearLayout actions = row();
            TextView add = button("Add files", false, this::openPicker);
            actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
            TextView clear = button("Clear", false, () -> {
                if (!busy) {
                    if (files.size() > 1) {
                        dialogBuilder()
                                .setTitle("Clear selected files")
                                .setMessage("Remove all " + files.size() + " files from the conversion queue?")
                                .setPositiveButton("Clear", (d, w) -> {
                                    clearInputFiles();
                                    batchArchive = null;
                                    showPage("Convert");
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        clearInputFiles();
                        batchArchive = null;
                        showPage("Convert");
                    }
                }
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(48), 1);
            cp.leftMargin = dp(10);
            actions.addView(clear, cp);
            LinearLayout.LayoutParams ap = lp(-1, 48);
            ap.topMargin = dp(12);
            body.addView(actions, ap);
            fileRows = column();
            LinearLayout.LayoutParams rp = lp(-1, -2);
            rp.topMargin = dp(14);
            body.addView(fileRows, rp);
            renderFileRows();

            boolean imageBatch = files.stream().allMatch(f -> Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg", "pdf").contains(f.type));
            boolean oneDocumentType = files.stream().allMatch(f -> f.type.equals(files.get(0).type) && Arrays.asList("docx", "xlsx", "xls", "pptx", "odt", "csv", "txt", "md", "html", "rtf").contains(f.type));
            if (!imageBatch && !oneDocumentType) {
                LinearLayout warningCard = column();
                pad(warningCard, 16, 16, 16, 16);
                int warnBg = isDark() ? Color.parseColor("#362816") : Color.parseColor("#FEF3C7");
                int warnStroke = Color.parseColor("#F59E0B");
                int warnTitleColor = isDark() ? Color.parseColor("#FDE68A") : Color.parseColor("#92400E");
                int warnDescColor = isDark() ? Color.parseColor("#FCD34D") : Color.parseColor("#78350F");
                warningCard.setBackground(shape(warnBg, 16, warnStroke));

                TextView warnTitle = text("Incompatible batch selection", 15, true, warnTitleColor);
                warningCard.addView(warnTitle, lp(-1, -2));

                TextView warnDesc = text("Selected files have different formats that cannot be converted together. Please choose files of the same type, or select images and PDFs.", 13, false, warnDescColor);
                LinearLayout.LayoutParams wdp = lp(-1, -2);
                wdp.topMargin = dp(6);
                warningCard.addView(warnDesc, wdp);

                body.addView(warningCard, layoutTop(16));

                resultRows = column();
                renderResults();
                body.addView(resultRows);
                return;
            }
            TextView fmt = text("Convert to", 15, true, ink);
            LinearLayout.LayoutParams fp = lp(-1, -2);
            fp.topMargin = dp(22);
            body.addView(fmt, fp);
            formatPicker = new Spinner(this);
            String[] options = availableFormats();
            formatPicker.setAdapter(createThemedAdapter(options));
            if (preferredTarget != null) {
                int target = -1;
                for (int idx = 0; idx < options.length; idx++) {
                    if (options[idx].equalsIgnoreCase(preferredTarget) || options[idx].toLowerCase(Locale.ROOT).startsWith(preferredTarget.toLowerCase(Locale.ROOT))) {
                        target = idx;
                        break;
                    }
                }
                if (target >= 0) formatPicker.setSelection(target);
                preferredTarget = null;
            }
            LinearLayout.LayoutParams sp = lp(-1, 54);
            sp.topMargin = dp(8);
            body.addView(formatPicker, sp);

            if (imageBatch) {
                TextView dpiLabel = text("Output resolution", 14, true, ink);
                LinearLayout.LayoutParams dlp = lp(-1, -2);
                dlp.topMargin = dp(12);
                body.addView(dpiLabel, dlp);
                Spinner dpiPicker = new Spinner(this);
                Integer[] dpiValues = {72, 96, 150, 300, 600};
                String[] dpiTitles = {"72 DPI", "96 DPI", "150 DPI", "300 DPI", "600 DPI"};
                dpiPicker.setAdapter(createThemedAdapter(dpiTitles));
                int selectedDpi = Arrays.asList(dpiValues).indexOf(outputDpi);
                dpiPicker.setSelection(Math.max(0, selectedDpi));
                dpiPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    public void onNothingSelected(AdapterView<?> p) {}
                    public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                        outputDpi = dpiValues[position];
                    }
                });
                body.addView(dpiPicker, lp(-1, 48));
            }

            String prevOutputName = outputNameInput != null ? outputNameInput.getText().toString() : "";
            String prevPassword = passwordInput != null ? passwordInput.getText().toString() : "";
            boolean prevProtectChecked = protectCheckbox != null && protectCheckbox.isChecked();

            outputNameInput = new EditText(this);
            outputNameInput.setSingleLine(true);
            outputNameInput.setHint(files.size() > 1 ? "File name prefix (optional)" : "File name (optional)");
            outputNameInput.setTextSize(14);
            outputNameInput.setPadding(dp(14), 0, dp(14), 0);
            styleEditText(outputNameInput);
            if (!prevOutputName.isEmpty()) {
                outputNameInput.setText(prevOutputName);
            }
            LinearLayout.LayoutParams namep = lp(-1, 50);
            namep.topMargin = dp(8);
            body.addView(outputNameInput, namep);

            passwordProtectionContainer = column();
            LinearLayout.LayoutParams ppContainerParams = lp(-1, -2);
            ppContainerParams.topMargin = dp(8);

            passwordInput = new EditText(this);
            passwordInput.setSingleLine(true);
            passwordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            passwordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
            passwordInput.setHint("Password protection (optional)");
            passwordInput.setTextSize(14);
            passwordInput.setPadding(dp(14), 0, dp(14), 0);
            styleEditText(passwordInput);
            if (!prevPassword.isEmpty()) {
                passwordInput.setText(prevPassword);
            }
            passwordProtectionContainer.addView(passwordInput, lp(-1, 50));

            protectCheckbox = new CheckBox(this);
            protectCheckbox.setText("Protect with password");
            protectCheckbox.setTextColor(ink);
            protectCheckbox.setTextSize(13);
            protectCheckbox.setChecked(prevProtectChecked);
            protectCheckbox.setOnCheckedChangeListener((btn, isChecked) -> {
                passwordInput.setEnabled(isChecked);
                passwordInput.setAlpha(isChecked ? 1.0f : 0.5f);
            });
            passwordInput.setEnabled(prevProtectChecked);
            passwordInput.setAlpha(prevProtectChecked ? 1.0f : 0.5f);
            passwordInput.setAlpha(0.5f);

            LinearLayout.LayoutParams cbParams = lp(-1, -2);
            cbParams.topMargin = dp(4);
            passwordProtectionContainer.addView(protectCheckbox, cbParams);

            body.addView(passwordProtectionContainer, ppContainerParams);

            formatPicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                public void onItemSelected(AdapterView<?> p, View v, int position, long id) {
                    updatePasswordProtectionVisibility();
                }
                public void onNothingSelected(AdapterView<?> p) {}
            });
            updatePasswordProtectionVisibility();

            if (files.stream().allMatch(f -> Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg").contains(f.type))) {
                imageOperationPicker = new Spinner(this);
                String[] edits = {"None", "Resize (Custom %)", "Resize 25%", "Resize 50%", "Resize 75%", "Crop center 50%", "Crop center 80%", "Rotate 90°", "Flip horizontal", "Flip vertical", "Grayscale"};
                imageOperationPicker.setAdapter(createThemedAdapter(edits));
                LinearLayout.LayoutParams ep = lp(-1, 50);
                ep.topMargin = dp(8);
                body.addView(imageOperationPicker, ep);

                LinearLayout resizeRow = column();
                TextView resizeLabel = text("Resize: " + imageResizePercent + "%", 13, true, muted);
                SeekBar resizeBar = new SeekBar(this);
                resizeBar.setMax(99);
                resizeBar.setProgress(imageResizePercent - 1);
                resizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar bar) {}
                    public void onStopTrackingTouch(SeekBar bar) {}
                    public void onProgressChanged(SeekBar bar, int value, boolean user) {
                        imageResizePercent = 1 + value;
                        resizeLabel.setText("Resize: " + imageResizePercent + "%");
                    }
                });
                resizeRow.addView(resizeLabel, lp(-1, -2));
                resizeRow.addView(resizeBar, lp(-1, 42));
                resizeRow.setVisibility(View.GONE);
                LinearLayout.LayoutParams rrp = lp(-1, -2);
                rrp.topMargin = dp(6);
                body.addView(resizeRow, rrp);

                imageOperationPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                    public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                        String selected = (String) parent.getItemAtPosition(position);
                        resizeRow.setVisibility("Resize (Custom %)".equals(selected) ? View.VISIBLE : View.GONE);
                    }
                    public void onNothingSelected(android.widget.AdapterView<?> parent) {}
                });

                TextView qlabel = text("Image quality  " + imageQuality + "%", 13, true, muted);
                SeekBar quality = new SeekBar(this);
                quality.setMax(80);
                quality.setProgress(imageQuality - 20);
                quality.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onStartTrackingTouch(SeekBar bar) {}
                    public void onStopTrackingTouch(SeekBar bar) {}
                    public void onProgressChanged(SeekBar bar, int value, boolean user) {
                        imageQuality = 20 + value;
                        qlabel.setText("Image quality  " + imageQuality + "%");
                    }
                });
                LinearLayout.LayoutParams qp = lp(-1, 44);
                body.addView(qlabel, qp);
                body.addView(quality, lp(-1, 42));
            }

            convertButton = button(busy ? "Cancel conversion" : "Convert files", true, () -> {
                if (busy) {
                    cancelBatch = true;
                } else {
                    startConversion();
                }
            });
            LinearLayout.LayoutParams bp = lp(-1, 56);
            bp.topMargin = dp(12);
            body.addView(convertButton, bp);

            convertAnimView = new FileCreationAnimationView(this);
            convertAnimView.setPalette(surface, ink, muted, accent, line);
            convertAnimView.setVisibility(busy ? View.VISIBLE : View.GONE);
            LinearLayout.LayoutParams cap = lp(-1, 80);
            cap.topMargin = dp(8);
            body.addView(convertAnimView, cap);
            if (busy) {
                convertAnimView.startConstruction();
            }

            progressText = text("", 14, false, muted);
            progressText.setGravity(Gravity.CENTER);
            body.addView(progressText, lp(-1, 42));
            resultRows = column();
            LinearLayout.LayoutParams outp = lp(-1, -2);
            outp.topMargin = dp(12);
            body.addView(resultRows, outp);
            renderResults();
        }
    }

    private String[] availableFormats() {
        if (!files.isEmpty() && files.stream().allMatch(f -> f.type.equals("pdf"))) {
            return new String[]{"PDF", "DOCX", "Searchable PDF", "OCR (DOCX)", "OCR (Latin)", "OCR (Devanagari)", "OCR (Chinese)", "OCR (Japanese)", "OCR (Korean)", "PNG", "JPG", "WebP", "TXT", "HTML", "Markdown"};
        }
        if (!files.isEmpty() && files.stream().allMatch(f -> f.type.equals(files.get(0).type))) {
            switch (files.get(0).type) {
                case "docx":
                    return new String[]{"PDF", "TXT", "HTML"};
                case "xlsx":
                    return new String[]{"PDF", "CSV"};
                case "xls":
                    return new String[]{"PDF", "CSV", "XLSX"};
                case "pptx":
                case "odt":
                    return new String[]{"PDF", "TXT", "HTML", "DOCX"};
                case "csv":
                    return new String[]{"PDF", "XLSX"};
                case "txt":
                    return new String[]{"PDF", "DOCX", "HTML"};
                case "md":
                    return new String[]{"PDF", "HTML", "DOCX"};
                case "html":
                    return new String[]{"PDF", "TXT"};
                case "rtf":
                    return new String[]{"PDF", "DOCX", "TXT"};
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("PDF");
        out.add("PNG");
        out.add("JPG");
        out.add("WebP");
        if (files.stream().allMatch(f -> Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg", "pdf").contains(f.type))) {
            out.add("Searchable PDF");
            out.add("OCR (DOCX)");
            out.add("OCR (Latin)");
            out.add("OCR (Devanagari)");
            out.add("OCR (Chinese)");
            out.add("OCR (Japanese)");
            out.add("OCR (Korean)");
        }
        if (files.stream().allMatch(f -> f.type.equals("gif"))) out.add("GIF (keep animation)");
        if (files.size() == 1) {
            String in = files.get(0).type;
            if (!in.equals("pdf")) {
                out.remove(in.equals("jpeg") ? "JPG" : in.toUpperCase(Locale.ROOT));
            }
        }
        return out.toArray(new String[0]);
    }

    private void renderFileRows() {
        fileRows.removeAllViews();
        int i = 0;
        for (InputFile f : files) {
            LinearLayout r = row();
            pad(r, 12, 11, 12, 11);
            r.setBackground(shape(surface, 15, line));
            if (Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg", "pdf").contains(f.type)) {
                ImageView preview = new ImageView(this);
                preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
                preview.setBackground(shape(bg, 10, 0));
                preview.setImageBitmap(f.preview);
                r.addView(preview, lp(52, 44));
                f.previewView = preview;
                preview.setOnClickListener(v -> showPreview(f));
                if (f.preview == null && !f.previewLoading) {
                    f.previewLoading = true;
                    worker.execute(() -> {
                        try {
                            Bitmap bitmap = f.type.equals("pdf") ? PdfTools.thumbnail(getContentResolver(), f.uri) : ImageTools.thumbnail(getContentResolver(), f.uri, f.type);
                            runOnUiThread(() -> {
                                f.preview = bitmap;
                                if (f.previewView != null) f.previewView.setImageBitmap(bitmap);
                            });
                        } catch (Exception ignored) {
                            f.previewLoading = false;
                        }
                    });
                }
            } else {
                TextView badge = text(f.type.toUpperCase(Locale.ROOT), 11, true, accent);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(shape(bg, 10, 0));
                r.addView(badge, lp(52, 40));
            }
            LinearLayout info = column();
            TextView name = text(f.name, 14, true, ink);
            name.setMaxLines(1);
            name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            info.addView(name);
            long fSize = f.size > 0 ? f.size : querySize(f.uri);
            if (f.size <= 0 && fSize > 0) f.size = fSize;
            info.addView(text(f.state + "  ·  " + sizeLabel(fSize) + (f.details.isEmpty() ? "" : "  ·  " + f.details), 12, false, muted));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
            ip.leftMargin = dp(10);
            r.addView(info, ip);
            if (f.output != null) {
                TextView checkmark = text("✓", 19, true, Color.parseColor("#10B981"));
                checkmark.setContentDescription("File converted successfully");
                r.addView(checkmark);
            }
            TextView remove = text("×", 22, false, muted);
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("Remove " + f.name);
            remove.setBackground(shape(bg, 22, 0));
            remove.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                return false;
            });
            remove.setOnClickListener(v -> {
                if (busy && f.state.equals("Waiting")) {
                    f.state = "Cancelled";
                    renderFileRows();
                } else if (!busy) {
                    if (f.preview != null && !f.preview.isRecycled()) {
                        f.preview.recycle();
                        f.preview = null;
                    }
                    if (f.previewView != null) {
                        f.previewView.setImageBitmap(null);
                        f.previewView = null;
                    }
                    files.remove(f);
                    showPage("Convert");
                }
            });
            LinearLayout.LayoutParams rm = lp(44, 44);
            rm.leftMargin = dp(8);
            r.addView(remove, rm);
            LinearLayout.LayoutParams rlp = lp(-1, -2);
            if (i++ > 0) rlp.topMargin = dp(7);
            fileRows.addView(r, rlp);
        }
    }

    private void clearInputFiles() {
        for (InputFile f : files) {
            if (f.preview != null && !f.preview.isRecycled()) {
                f.preview.recycle();
                f.preview = null;
            }
            if (f.previewView != null) {
                f.previewView.setImageBitmap(null);
                f.previewView = null;
            }
        }
        files.clear();
        uriSizeCache.clear();
        uriNameCache.clear();
        uriTypeCache.clear();
    }

    private void renderResults() {
        if (resultRows == null) return;
        resultRows.removeAllViews();
        boolean any = false, allDone = !files.isEmpty();
        int outputCount = 0;
        ArrayList<OutputFile> allOutputs = new ArrayList<>();
        for (InputFile f : files) {
            if (f.output == null) {
                if (!f.state.equals("Included in combined PDF")) allDone = false;
                continue;
            }
            any = true;
            ArrayList<OutputFile> results = new ArrayList<>();
            results.add(f.output);
            results.addAll(f.extraOutputs);
            outputCount += results.size();
            allOutputs.addAll(results);
            for (OutputFile output : results) {
                LinearLayout r = column();
                pad(r, 14, 14, 14, 14);
                r.setBackground(shape(surface, 16, line));

                LinearLayout badgeRow = row();
                TextView checkIcon = text("✓", 13, true, Color.parseColor("#10B981"));
                badgeRow.addView(checkIcon, lp(-2, -2));
                TextView savedBadge = text(" File saved successfully", 12, true, Color.parseColor("#10B981"));
                badgeRow.addView(savedBadge, lp(-2, -2));
                r.addView(badgeRow, lp(-1, -2));

                TextView nameView = text(output.name, 14, true, ink);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                LinearLayout.LayoutParams nvp = lp(-1, -2);
                nvp.topMargin = dp(4);
                r.addView(nameView, nvp);

                if (output.sourceSize > 0) {
                    long outSize = output.file.length();
                    String metric = sizeLabel(output.sourceSize) + "  →  " + sizeLabel(outSize);
                    if (outSize < output.sourceSize)
                        metric += "  ·  " + Math.round((1f - outSize / (float) output.sourceSize) * 100) + "% smaller";
                    r.addView(text(metric, 12, false, muted));
                } else {
                    r.addView(text(sizeLabel(output.file.length()) + " · Saved in FileSwitch folder", 12, false, muted));
                }

                LinearLayout acts = column();
                LinearLayout r1 = row();
                TextView open = button("Open File", true, () -> openOutput(output));
                TextView share = button("Share", false, () -> shareOutput(output));
                r1.addView(open, new LinearLayout.LayoutParams(0, dp(44), 1));
                View sp1 = new View(this);
                r1.addView(sp1, lp(8, 1));
                r1.addView(share, new LinearLayout.LayoutParams(0, dp(44), 1));
                acts.addView(r1, lp(-1, -2));

                LinearLayout r2 = row();
                TextView saveAs = button("Save As", false, () -> saveOutput(output));
                TextView done = button("Done", false, () -> {
                    clearInputFiles();
                    renderFileRows();
                    renderResults();
                });
                r2.addView(saveAs, new LinearLayout.LayoutParams(0, dp(44), 1));
                View sp2 = new View(this);
                r2.addView(sp2, lp(8, 1));
                r2.addView(done, new LinearLayout.LayoutParams(0, dp(44), 1));
                if ("text/plain".equals(output.mime)) {
                    View sp3 = new View(this);
                    r2.addView(sp3, lp(8, 1));
                    TextView copy = button("Copy", false, () -> copyTextOutput(output));
                    r2.addView(copy, new LinearLayout.LayoutParams(0, dp(44), 1));
                }
                LinearLayout.LayoutParams r2p = lp(-1, -2);
                r2p.topMargin = dp(8);
                acts.addView(r2, r2p);
                LinearLayout.LayoutParams p = lp(-1, -2);
                p.topMargin = dp(12);
                r.addView(acts, p);
                resultRows.addView(r, lp(-1, -2));
            }
        }

        if (allOutputs.size() > 1) {
            LinearLayout batchRow = row();
            TextView shareAll = button("Share all files", false, () -> shareMultipleOutputs(allOutputs));
            batchRow.addView(shareAll, new LinearLayout.LayoutParams(0, dp(50), 1));
            LinearLayout.LayoutParams bp = lp(-1, 50);
            bp.topMargin = dp(10);
            resultRows.addView(batchRow, bp);
        }

        long failed = files.stream().filter(f -> f.state.startsWith("Failed")).count();
        if (failed > 0 && !busy) {
            TextView retry = button("Retry " + failed + " failed file" + (failed == 1 ? "" : "s"), false, () -> {
                retryFailedOnly = true;
                startConversion();
            });
            LinearLayout.LayoutParams retryParams = lp(-1, 50);
            retryParams.topMargin = dp(10);
            resultRows.addView(retry, retryParams);
        }

        if (allDone && outputCount > 1) {
            if (batchArchive == null) {
                TextView zip = button("Prepare all files as ZIP", true, () -> worker.execute(() -> {
                    try {
                        OutputFile archive = createBatchArchive();
                        runOnUiThread(() -> {
                            batchArchive = archive;
                            renderResults();
                        });
                    } catch (Exception e) {
                        runOnUiThread(() -> toast("Could not create ZIP"));
                    }
                }));
                LinearLayout.LayoutParams z = lp(-1, 52);
                z.topMargin = dp(10);
                resultRows.addView(zip, z);
            } else {
                LinearLayout zipActions = row();
                for (TextView action : new TextView[]{button("Save ZIP", true, () -> saveOutput(batchArchive)), button("Share ZIP", false, () -> shareOutput(batchArchive))}) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(50), 1);
                    p.leftMargin = dp(4);
                    p.rightMargin = dp(4);
                    zipActions.addView(action, p);
                }
                LinearLayout.LayoutParams z = lp(-1, 52);
                z.topMargin = dp(10);
                resultRows.addView(zipActions, z);
            }
        }
        resultRows.setVisibility(any || failed > 0 ? View.VISIBLE : View.GONE);
    }

    private void deleteOutput(OutputFile f) {
        dialogBuilder()
                .setTitle("Delete file")
                .setMessage("Delete converted file " + f.name + "?")
                .setPositiveButton("Delete", (d, w) -> {
                    if (f.file.exists()) f.file.delete();
                    for (InputFile in : files) {
                        if (in.output == f) in.output = null;
                        in.extraOutputs.remove(f);
                    }
                    renderResults();
                    renderFileRows();
                    toast("File deleted");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyTextOutput(OutputFile f) {
        worker.execute(() -> {
            try (FileInputStream in = new FileInputStream(f.file); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] b = new byte[16384];
                int n;
                while ((n = in.read(b)) > 0) out.write(b, 0, n);
                String content = new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
                runOnUiThread(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Recognized text", content));
                        toast("Text copied to clipboard");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not read text"));
            }
        });
    }

    private void shareMultipleOutputs(ArrayList<OutputFile> outputs) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (OutputFile out : outputs) {
            uris.add(outputUri(out));
        }
        if (uris.isEmpty()) return;
        Intent intent = new Intent(Intent.ACTION_SEND_MULTIPLE);
        intent.setType("*/*");
        intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share converted files"));
    }

    private long querySize(Uri uri) {
        if (uri == null) return 0;
        Long cached = uriSizeCache.get(uri);
        if (cached != null) return cached;
        long size = 0;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) size = c.getLong(n);
            }
        } catch (Exception ignored) {}
        if (size > 0) uriSizeCache.put(uri, size);
        return size;
    }

    private String sizeLabel(long n) {
        return n < 1024 * 1024 ? Math.max(1, n / 1024) + " KB" : String.format(Locale.getDefault(), "%.1f MB", n / (1024f * 1024));
    }

    private void showPreview(InputFile file) {
        if (file.preview == null) return;
        Dialog dialog = new Dialog(this);
        LinearLayout content = column();
        content.setGravity(Gravity.CENTER);
        content.setBackgroundColor(Color.rgb(20, 22, 26));
        pad(content, 18, 18, 18, 18);
        ImageView image = new ImageView(this);
        image.setImageBitmap(file.preview);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        content.addView(image, new LinearLayout.LayoutParams(-1, 0, 1));
        TextView name = text(file.name, 15, true, Color.WHITE);
        name.setGravity(Gravity.CENTER);
        content.addView(name, lp(-1, 36));
        String details = (file.details.isEmpty() ? file.type.toUpperCase(Locale.ROOT) : file.details) + " · " + sizeLabel(querySize(file.uri));
        TextView info = text(details, 12, false, Color.LTGRAY);
        info.setGravity(Gravity.CENTER);
        content.addView(info, lp(-1, 30));
        TextView close = button("Done", true, dialog::dismiss);
        content.addView(close, layoutTop(8));
        dialog.setContentView(content);
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            dialog.getWindow().setLayout(-1, -1);
        }
    }

    private void openPicker() {
        if (busy) {
            toast("Conversion is currently in progress");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "text/*", "application/rtf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-excel", "application/vnd.oasis.opendocument.text"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, PICK_FILES);
        } catch (ActivityNotFoundException e) {
            toast("No file picker is available");
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == PICK_TOOL_FILE && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (!picked.isEmpty()) {
                int count = picked.size();
                toast("✓ " + count + (count == 1 ? " file selected" : " files selected"));
                onToolFilesSelected(picked);
            }
            return;
        }
        if (code == PICK_TOOL_SECONDARY_FILE && result == RESULT_OK && data != null && data.getData() != null) {
            toolSecondaryUri = data.getData();
            toast("✓ Image selected");
            worker.execute(() -> {
                try (InputStream in = getContentResolver().openInputStream(toolSecondaryUri)) {
                    toolSecondaryBitmap = BitmapFactory.decodeStream(in);
                } catch (Exception ignored) {}
                runOnUiThread(this::renderToolPage);
            });
            return;
        }
        if (code == SAVE_FILE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                OutputFile f = pendingSave;
                pendingSave = null;
                worker.execute(() -> copyTo(f, data.getData()));
            } else pendingSave = null;
            return;
        }
        if (code == PICK_ZIP && result == RESULT_OK && data != null && data.getData() != null) {
            toast("✓ 1 archive selected");
            extractZip(data.getData());
            return;
        }
        if (code == PICK_CREATE_ZIP && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) {
                int count = picked.size();
                toast("✓ " + count + (count == 1 ? " file selected" : " files selected"));
                promptCreateZip(picked);
            }
            return;
        }
        if (code == PICK_IMAGE_FOR_PDF && result == RESULT_OK && data != null && data.getData() != null) {
            toast("✓ 1 image selected");
            requestPdfImagePage(data.getData());
            return;
        }
        if (code == PICK_PDF_TOOL && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) {
                int count = picked.size();
                toast("✓ " + count + (count == 1 ? " file selected" : " files selected"));
                requestPdfOptions(pendingPdfTool, picked);
            }
            return;
        }
        if (code == PICK_EXIF_IMAGE && result == RESULT_OK && data != null && data.getData() != null) {
            toast("✓ 1 image selected");
            showExifEditDialog(data.getData());
            return;
        }
        if (code == PICK_CONTACT_SHEET && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) {
                int count = picked.size();
                toast("✓ " + count + (count == 1 ? " image selected" : " images selected"));
                showContactSheetDialog(picked);
            }
            return;
        }
        if (code == PICK_REPAIR_DOC && result == RESULT_OK && data != null && data.getData() != null) {
            toast("✓ 1 document selected");
            repairDocument(data.getData());
            return;
        }
        if (code != PICK_FILES || result != RESULT_OK || data == null) return;
        ArrayList<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                picked.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) picked.add(data.getData());
        if (!picked.isEmpty()) {
            int count = picked.size();
            toast("✓ " + count + (count == 1 ? " file selected" : " files selected"));
        }
        addPickedFiles(picked);
    }

    private void addPickedFiles(List<Uri> picked) {
        if (picked == null || picked.isEmpty()) return;
        int validCount = 0;
        int duplicateCount = 0;
        ArrayList<String> rejectedReasons = new ArrayList<>();

        for (Uri uri : picked) {
            String name = displayName(uri), type = detectType(uri, name);
            if (!Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg", "pdf", "docx", "xlsx", "pptx", "odt", "xls", "csv", "txt", "md", "html", "rtf").contains(type)) {
                rejectedReasons.add(name + ": unsupported format");
                continue;
            }
            if (querySize(uri) > MAX_FILE) {
                rejectedReasons.add(name + ": exceeds 100 MB limit");
                continue;
            }
            if (files.stream().anyMatch(f -> f.uri.equals(uri))) {
                duplicateCount++;
                continue;
            }
            InputFile file = new InputFile(uri, name, type);
            files.add(file);
            validCount++;
            if (type.equals("pdf")) worker.execute(() -> {
                try {
                    file.details = PdfTools.metadata(getContentResolver(), uri).replace('\n', ' ');
                    runOnUiThread(() -> {
                        if (fileRows != null && page.equals("Convert")) renderFileRows();
                    });
                } catch (Exception ignored) {}
            });
            else if (Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif").contains(type))
                worker.execute(() -> {
                    try {
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            BitmapFactory.decodeStream(in, null, bounds);
                        }
                        int dpi = ImageTools.readDpi(getContentResolver(), uri);
                        file.details = bounds.outWidth + " × " + bounds.outHeight + " px · " + type.toUpperCase(Locale.ROOT) + (dpi > 0 ? " · " + dpi + " DPI" : "");
                        runOnUiThread(() -> {
                            if (fileRows != null && page.equals("Convert")) renderFileRows();
                        });
                    } catch (Exception ignored) {}
                });
        }
        if (validCount > 0) {
            justImportedCount = validCount;
            isImportingAnimation = true;
            if (!rejectedReasons.isEmpty()) {
                toast("Added " + validCount + " valid file(s). " + rejectedReasons.get(0) + (rejectedReasons.size() > 1 ? " (+" + (rejectedReasons.size() - 1) + " others skipped)" : ""));
            }
        } else if (!rejectedReasons.isEmpty()) {
            toast("No valid files added. " + rejectedReasons.get(0));
        } else if (duplicateCount > 0) {
            toast(duplicateCount == 1 ? "File is already selected" : "Selected files are already added");
        }
        showPage("Convert");
    }

    private OutputFile createBatchArchive() throws IOException {
        File dir = new File(getCacheDir(), "fileswitch");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare ZIP");
        File zip = new File(dir, UUID.randomUUID() + "-FileSwitch-results.zip");
        HashSet<String> names = new HashSet<>();
        try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
            for (InputFile input : files) {
                ArrayList<OutputFile> results = new ArrayList<>();
                if (input.output != null) {
                    results.add(input.output);
                    results.addAll(input.extraOutputs);
                }
                for (OutputFile result : results) {
                    String name = result.name;
                    int copy = 2;
                    while (!names.add(name)) {
                        int dot = name.lastIndexOf('.');
                        name = dot > 0 ? name.substring(0, dot) + " (" + copy++ + ")" + name.substring(dot) : name + " (" + copy++ + ")";
                    }
                    out.putNextEntry(new ZipEntry(name));
                    try (InputStream in = new FileInputStream(result.file)) {
                        byte[] buffer = new byte[32768];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    }
                    out.closeEntry();
                }
            }
        } catch (IOException error) {
            if (zip.exists()) zip.delete();
            throw error;
        }
        if (!zip.exists() || zip.length() <= 0) {
            if (zip.exists()) zip.delete();
            throw new IOException("ZIP archive could not be created or is empty");
        }
        OutputFile saved = saveOutputFileToFileSwitchDir(new OutputFile(zip, "FileSwitch-results.zip", "application/zip", "zip"));
        if (saved == null || saved.file == null || !saved.file.exists() || saved.file.length() <= 0) {
            throw new IOException("Could not save ZIP archive to FileSwitch folder");
        }
        return saved;
    }

    private String displayName(Uri uri) {
        if (uri == null) return "file";
        String cached = uriNameCache.get(uri);
        if (cached != null) return cached;
        String name = "file";
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) {
                    String str = c.getString(i);
                    if (str != null && !str.isEmpty()) name = str;
                }
            }
        } catch (Exception ignored) {}
        uriNameCache.put(uri, name);
        return name;
    }

    private String detectType(Uri uri) {
        return detectType(uri, displayName(uri));
    }

    private String detectType(Uri uri, String name) {
        if (uri == null) return "";
        String cached = uriTypeCache.get(uri);
        if (cached != null) return cached;
        String result = "";
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) result = "png";
            else if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) result = "jpg";
            else if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
                result = "webp";
            else if (n >= 2 && b[0] == 'B' && b[1] == 'M') result = "bmp";
            else if (n >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') result = "gif";
            else if (n >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') result = "pdf";
            else {
                String sample = new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8).trim();
                if (sample.matches("(?is)(<\\?xml[^>]*>\\s*)?<svg[\\s>].*")) result = "svg";
                else if (n >= 12 && new String(b, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")) {
                    String brand = new String(b, 8, Math.min(n - 8, 16), java.nio.charset.StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                    if (brand.contains("heic") || brand.contains("heif") || brand.contains("heix") || brand.contains("hevc") || brand.contains("heim") || brand.contains("heis") || brand.contains("mif1") || brand.contains("msf1")) result = "heic";
                } else if (n >= 4 && b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5 || b[2] == 7) && (b[3] == 4 || b[3] == 6 || b[3] == 8)) {
                    String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                    if (Arrays.asList("docx", "xlsx", "pptx", "odt").contains(ext)) result = ext;
                    else result = "zip";
                }
            }
        } catch (Exception ignored) {}
        if (result.isEmpty()) {
            String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
            if (Arrays.asList("docx", "xlsx", "pptx", "odt", "xls", "zip").contains(ext)) result = ext;
            else result = Arrays.asList("csv", "txt", "md", "html", "rtf", "heic", "heif", "svg", "jpg", "jpeg", "png", "webp", "bmp", "gif", "pdf").contains(ext) ? (ext.equals("jpeg") ? "jpg" : ext) : "";
        }
        if (!result.isEmpty()) uriTypeCache.put(uri, result);
        return result;
    }

    private String targetFormat() {
        if (formatPicker == null || formatPicker.getSelectedItem() == null) return "";
        String selected = ((String) formatPicker.getSelectedItem()).toLowerCase(Locale.ROOT);
        return selected.equals("markdown") ? "md"
                : selected.equals("ocr (latin)") ? "ocr-latin"
                : selected.equals("ocr (devanagari)") ? "ocr-devanagari"
                : selected.equals("ocr (chinese)") ? "ocr-chinese"
                : selected.equals("ocr (japanese)") ? "ocr-japanese"
                : selected.equals("ocr (korean)") ? "ocr-korean"
                : selected.equals("ocr (docx)") ? "ocr-docx"
                : selected.equals("searchable pdf") ? "searchable-pdf"
                : selected.equals("gif (keep animation)") ? "gif"
                : selected;
    }

    private void updatePasswordProtectionVisibility() {
        if (passwordProtectionContainer == null) return;
        String target = targetFormat();
        boolean isSupported = "pdf".equalsIgnoreCase(target) || "searchable-pdf".equalsIgnoreCase(target);
        passwordProtectionContainer.setVisibility(isSupported ? View.VISIBLE : View.GONE);
    }

    private void startConversion() {
        if (busy) return;
        if (files.isEmpty()) {
            toast("Please select at least one file to convert");
            return;
        }
        if (formatPicker == null || formatPicker.getSelectedItem() == null) {
            toast("Please choose an output format");
            return;
        }
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            return;
        }
        boolean onlyFailed = retryFailedOnly;
        retryFailedOnly = false;
        final String target = targetFormat();
        final boolean enablePassword = protectCheckbox != null && protectCheckbox.isChecked() && ("pdf".equals(target) || "searchable-pdf".equals(target));
        final String password = (enablePassword && passwordInput != null) ? passwordInput.getText().toString() : "";
        if (enablePassword && password.length() < 4) {
            toast("Password must be at least 4 characters");
            return;
        }
        final String effectivePassword = enablePassword ? password : null;

        final String rawEdit = imageOperationPicker == null ? "None" : (String) imageOperationPicker.getSelectedItem();
        final String edit = "Resize (Custom %)".equals(rawEdit) ? "Resize " + imageResizePercent + "%" : rawEdit;
        final int quality = imageQuality;
        busy = true;
        cancelBatch = false;
        batchArchive = null;
        ArrayList<InputFile> queue = new ArrayList<>();
        for (InputFile f : files) {
            if (!onlyFailed || f.state.startsWith("Failed")) {
                f.output = null;
                f.extraOutputs.clear();
                f.state = "Waiting";
                queue.add(f);
            }
        }
        if (queue.isEmpty()) {
            busy = false;
            return;
        }
        renderFileRows();
        renderResults();
        convertButton.setText("Cancel conversion");
        if (convertAnimView != null) {
            convertAnimView.setVisibility(View.VISIBLE);
            convertAnimView.startConstruction();
        }
        final String customName = outputNameInput == null ? "" : outputNameInput.getText().toString().trim();
        final String prefix = safeName(customName);
        int total = queue.size();

        if (target.equals("pdf") && queue.size() > 1 && queue.stream().allMatch(f -> !f.type.equals("pdf"))) {
            worker.execute(() -> convertImagesToPdf(queue, edit, customName, effectivePassword));
            return;
        }

        worker.execute(() -> {
            int completed = 0;
            int successful = 0;
            OutputFile firstSuccessOutput = null;
            for (InputFile f : queue) {
                if (cancelBatch || Thread.currentThread().isInterrupted()) {
                    f.output = null;
                    f.extraOutputs.clear();
                    f.state = cancelBatch ? "Cancelled" : "Interrupted";
                    saveHistory(f.name, f.type, target, 0, cancelBatch ? "cancelled" : "interrupted", null, null);
                    continue;
                }
                int current = ++completed;
                notifManager.showProgress(current, total, f.name);
                runOnUiThread(() -> {
                    f.state = "Converting";
                    progressText.setText("File " + current + " of " + total + " · " + f.name);
                    renderFileRows();
                });
                try {
                    String singleCustomName = (queue.size() == 1 && !customName.isEmpty()) ? customName : null;
                    OutputFile converted = convertFile(f, target, edit, quality, singleCustomName, effectivePassword);
                    if (converted == null || converted.file == null || !converted.file.exists() || converted.file.length() <= 0) {
                        throw new IOException("Converted output file could not be created or is empty");
                    }
                    f.output = converted;
                    f.output.sourceSize = querySize(f.uri);
                    for (OutputFile out : f.extraOutputs) {
                        if (out == null || out.file == null || !out.file.exists() || out.file.length() <= 0) {
                            throw new IOException("Converted page could not be created or is empty");
                        }
                        out.sourceSize = f.output.sourceSize;
                    }
                    if (!prefix.isEmpty() && queue.size() > 1) renameBatchOutputs(f, prefix, current);
                    OutputFile saved = saveOutputFileToFileSwitchDir(f.output);
                    if (saved == null || saved.file == null || !saved.file.exists() || saved.file.length() <= 0) {
                        throw new IOException("Could not save converted file to FileSwitch folder");
                    }
                    f.output = saved;
                    for (int ei = 0; ei < f.extraOutputs.size(); ei++) {
                        OutputFile savedExtra = saveOutputFileToFileSwitchDir(f.extraOutputs.get(ei));
                        if (savedExtra == null || savedExtra.file == null || !savedExtra.file.exists() || savedExtra.file.length() <= 0) {
                            throw new IOException("Could not save converted page to FileSwitch folder");
                        }
                        f.extraOutputs.set(ei, savedExtra);
                    }
                    f.state = "Ready";
                    successful++;
                    if (firstSuccessOutput == null) firstSuccessOutput = f.output;
                    saveHistory(f.name, f.type, target, f.output.file.length(), "complete", f.output.file.getAbsolutePath(), f.output.mime);
                } catch (Exception e) {
                    f.output = null;
                    f.extraOutputs.clear();
                    String msg = getHumanReadableErrorMessage(e, "Could not convert");
                    f.state = "Failed · " + msg;
                    saveHistory(f.name, f.type, target, 0, "failed", null, null);
                }
                runOnUiThread(() -> {
                    renderFileRows();
                    renderResults();
                });
            }
            final int finalSuccess = successful;
            final OutputFile autoOpenTarget = firstSuccessOutput;
            if (cancelBatch) {
                notifManager.showFailed("Conversion cancelled");
            } else if (finalSuccess == total) {
                notifManager.showComplete(finalSuccess);
            } else if (finalSuccess > 0) {
                notifManager.showBatchResult(finalSuccess, total - finalSuccess);
            } else {
                notifManager.showFailed("All files failed to convert");
            }
            runOnUiThread(() -> {
                busy = false;
                convertButton.setText("Convert files");
                if (convertAnimView != null) {
                    if (finalSuccess > 0 && !cancelBatch) {
                        convertAnimView.finishSuccess(() -> {
                            if (convertAnimView != null) convertAnimView.setVisibility(View.GONE);
                        });
                    } else {
                        convertAnimView.stopImmediate();
                        convertAnimView.setVisibility(View.GONE);
                    }
                }
                if (cancelBatch) {
                    progressText.setText("Conversion cancelled");
                } else if (finalSuccess == 0) {
                    progressText.setText("Conversion failed · Check file and settings");
                } else if (finalSuccess < total) {
                    progressText.setText("Finished · " + finalSuccess + " of " + total + " files converted successfully");
                } else {
                    progressText.setText("Finished · Save, share or open your files");
                }
                renderFileRows();
                renderResults();
                if (getPreferences(0).getBoolean("auto_open_result", false) && autoOpenTarget != null && !cancelBatch) {
                    openOutput(autoOpenTarget);
                }
            });
        });
    }

    private void convertImagesToPdf(ArrayList<InputFile> queue, String edit, String customName, String password) {
        ArrayList<Bitmap> pages = new ArrayList<>();
        File out = null;
        try {
            long sourceSize = 0;
            for (int i = 0; i < queue.size(); i++) {
                if (cancelBatch || Thread.currentThread().isInterrupted()) throw new IOException("Conversion cancelled");
                InputFile input = queue.get(i);
                final int current = i + 1;
                notifManager.showProgress(current, queue.size(), input.name);
                runOnUiThread(() -> {
                    input.state = "Converting";
                    progressText.setText("Preparing image " + current + " of " + queue.size());
                    renderFileRows();
                });
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                if (!input.type.equals("svg")) {
                    bounds.inJustDecodeBounds = true;
                    try (InputStream stream = getContentResolver().openInputStream(input.uri)) {
                        BitmapFactory.decodeStream(stream, null, bounds);
                    }
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || (long) bounds.outWidth * bounds.outHeight > MAX_PIXELS)
                        throw new IOException(input.name + " exceeds the 20 megapixel limit or is unreadable");
                }
                Bitmap bitmap = ImageTools.decode(getContentResolver(), input.uri, input.type);
                Bitmap edited = ImageTools.edit(bitmap, edit);
                if (edited != bitmap) bitmap.recycle();
                pages.add(edited);
                sourceSize += querySize(input.uri);
            }
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            String name = (customName == null || customName.trim().isEmpty()) ? "images.pdf" : ensureExtension(customName, "pdf");
            out = new File(dir, UUID.randomUUID() + "-" + safeName(name));
            writePdf(pages, out);
            if (password != null && !password.isEmpty()) {
                PdfOperations.protectInPlace(out, password);
            }
            if (!out.exists() || out.length() <= 0) {
                throw new IOException("Combined PDF could not be created or is empty");
            }
            OutputFile result = new OutputFile(out, name, "application/pdf", "pdf");
            result.sourceSize = sourceSize;
            result = saveOutputFileToFileSwitchDir(result);
            if (result == null || result.file == null || !result.file.exists() || result.file.length() <= 0) {
                throw new IOException("Could not save combined PDF to FileSwitch folder");
            }
            InputFile first = queue.get(0);
            first.output = result;
            first.state = "Ready";
            for (int i = 1; i < queue.size(); i++) {
                queue.get(i).output = null;
                queue.get(i).state = "Included in combined PDF";
            }
            for (InputFile input : queue) saveHistory(input.name, input.type, "pdf", result.file.length(), "complete", result.file.getAbsolutePath(), "application/pdf");
            notifManager.showComplete(1);
        } catch (Exception e) {
            if (out != null) out.delete();
            String message = getHumanReadableErrorMessage(e, "Could not create PDF");
            for (InputFile input : queue) {
                input.output = null;
                input.state = (cancelBatch ? "Cancelled" : "Failed · " + message);
                saveHistory(input.name, input.type, "pdf", 0, cancelBatch ? "cancelled" : "failed", null, null);
            }
            notifManager.showFailed(message);
        } finally {
            for (Bitmap bitmap : pages) bitmap.recycle();
            final OutputFile combinedOutput = queue.get(0).output;
            runOnUiThread(() -> {
                busy = false;
                convertButton.setText("Convert files");
                if (convertAnimView != null) {
                    if (combinedOutput != null && combinedOutput.file != null && combinedOutput.file.exists() && combinedOutput.file.length() > 0 && !cancelBatch) {
                        convertAnimView.finishSuccess(() -> {
                            if (convertAnimView != null) convertAnimView.setVisibility(View.GONE);
                        });
                    } else {
                        convertAnimView.stopImmediate();
                        convertAnimView.setVisibility(View.GONE);
                    }
                }
                if (cancelBatch) {
                    progressText.setText("Conversion cancelled");
                } else if (combinedOutput != null && combinedOutput.file != null && combinedOutput.file.exists() && combinedOutput.file.length() > 0) {
                    progressText.setText("Finished · Save, share or open your files");
                } else {
                    progressText.setText("Conversion failed · Check file and settings");
                }
                renderFileRows();
                renderResults();
                if (getPreferences(0).getBoolean("auto_open_result", false) && combinedOutput != null && !cancelBatch) {
                    openOutput(combinedOutput);
                }
            });
        }
    }

    private void renameBatchOutputs(InputFile input, String prefix, int index) throws IOException {
        String number = String.format(Locale.ROOT, "%03d", index);
        ArrayList<OutputFile> outputs = new ArrayList<>();
        if (input.output != null) outputs.add(input.output);
        outputs.addAll(input.extraOutputs);
        int part = 0;
        String cleanPrefix = safeName(prefix.replaceFirst("\\.[^.]*$", ""));
        if (cleanPrefix.isEmpty()) cleanPrefix = prefix;
        for (OutputFile output : outputs) {
            String ext = output.name.contains(".") ? output.name.substring(output.name.lastIndexOf('.')) : "";
            String name = cleanPrefix + "_" + number + (part++ == 0 ? "" : "_" + part) + ext;
            File renamed = new File(output.file.getParentFile(), UUID.randomUUID() + "-" + name);
            if (!output.file.renameTo(renamed)) throw new IOException("Could not rename a converted file");
            output.file = renamed;
            output.name = name;
        }
    }

    private OutputFile convertFile(InputFile input, String target, String edit, int quality, String customName, String password) throws Exception {
        String base = (customName != null && !customName.trim().isEmpty()) ? safeName(customName.trim().replaceFirst("\\.[^.]*$", "")) : input.name.replaceFirst("\\.[^.]*$", "");
        if (base.isEmpty()) base = "converted-file";

        if (target.equals("searchable-pdf")) {
            File dir = new File(getCacheDir(), "fileswitch");
            File out = OcrTools.searchablePdf(getContentResolver(), input.uri, input.type, "latin", dir);
            if (password != null && !password.isEmpty()) {
                PdfOperations.protectInPlace(out, password);
            }
            String outName = (customName != null && !customName.trim().isEmpty()) ? ensureExtension(customName, "pdf") : (base + "-searchable.pdf");
            return new OutputFile(out, outName, "application/pdf", "pdf");
        }
        if (target.startsWith("ocr")) {
            String lang = "latin";
            if (target.contains("devanagari")) lang = "devanagari";
            else if (target.contains("chinese")) lang = "chinese";
            else if (target.contains("japanese")) lang = "japanese";
            else if (target.contains("korean")) lang = "korean";

            if (target.equals("ocr-docx")) {
                String recognized = input.type.equals("pdf") ? OcrTools.pdfText(getContentResolver(), input.uri, lang) : OcrTools.imageText(getContentResolver(), input.uri, input.type, lang);
                File dir = new File(getCacheDir(), "fileswitch");
                String outName = (customName != null && !customName.trim().isEmpty()) ? ensureExtension(customName, "docx") : (base + ".docx");
                File out = OfficeTools.writeDocx(recognized, dir, outName);
                return new OutputFile(out, outName, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            }
            String recognized = input.type.equals("pdf") ? OcrTools.pdfText(getContentResolver(), input.uri, lang) : OcrTools.imageText(getContentResolver(), input.uri, input.type, lang);
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            String outName = (customName != null && !customName.trim().isEmpty()) ? ensureExtension(customName, "txt") : (base + ".txt");
            File out = new File(dir, UUID.randomUUID() + "-" + safeName(outName));
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(recognized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new OutputFile(out, outName, "text/plain", "txt");
        }
        if (target.equals("gif") && input.type.equals("gif")) {
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            String outName = (customName != null && !customName.trim().isEmpty()) ? ensureExtension(customName, "gif") : (base + ".gif");
            File out = new File(dir, UUID.randomUUID() + "-" + safeName(outName));
            try (InputStream source = getContentResolver().openInputStream(input.uri); OutputStream destination = new FileOutputStream(out)) {
                if (source == null) throw new IOException("GIF could not be opened");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = source.read(buffer)) > 0) destination.write(buffer, 0, n);
            }
            return new OutputFile(out, outName, "image/gif", "gif");
        }
        if (input.type.equals("pdf")) {
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            String nameForConversion = (customName != null && !customName.trim().isEmpty()) ? customName : input.name;
            ArrayList<PdfTools.Result> converted = PdfTools.convert(getContentResolver(), input.uri, nameForConversion, target, outputDpi, dir);
            if (converted.isEmpty()) throw new IOException("This PDF has no pages");
            for (int i = 1; i < converted.size(); i++) {
                PdfTools.Result result = converted.get(i);
                if (target.equals("pdf") && password != null && !password.isEmpty()) {
                    PdfOperations.protectInPlace(result.file, password);
                }
                input.extraOutputs.add(new OutputFile(result.file, result.name, result.mime, target));
            }
            PdfTools.Result first = converted.get(0);
            if (target.equals("pdf") && password != null && !password.isEmpty()) {
                PdfOperations.protectInPlace(first.file, password);
            }
            return new OutputFile(first.file, first.name, first.mime, target);
        }
        if (Arrays.asList("docx", "xlsx", "xls", "pptx", "odt", "csv", "txt", "md", "html", "rtf").contains(input.type)) {
            File dir = new File(getCacheDir(), "fileswitch");
            String nameForConversion = (customName != null && !customName.trim().isEmpty()) ? customName : input.name;
            OfficeTools.Result result = OfficeTools.convert(getContentResolver(), input.uri, nameForConversion, input.type, target, dir);
            if (target.equals("pdf") && password != null && !password.isEmpty()) {
                PdfOperations.protectInPlace(result.file, password);
            }
            return new OutputFile(result.file, result.name, result.mime, target);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        if (!input.type.equals("svg")) {
            bounds.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(input.uri)) {
                BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("Image could not be opened");
            if ((long) bounds.outWidth * bounds.outHeight > MAX_PIXELS)
                throw new IOException("Image is too large for this phone");
        }
        Bitmap original = ImageTools.decode(getContentResolver(), input.uri, input.type);
        Bitmap bitmap = ImageTools.edit(original, edit);
        if (bitmap != original) original.recycle();
        String ext = target.equals("jpg") ? "jpg" : target;
        String outName = (customName != null && !customName.trim().isEmpty()) ? ensureExtension(customName, ext) : (base + "." + ext);
        File dir = new File(getCacheDir(), "fileswitch");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
        File out = new File(dir, UUID.randomUUID() + "-" + safeName(outName));
        try {
            if (target.equals("pdf")) {
                writePdf(bitmap, out);
                if (password != null && !password.isEmpty()) {
                    PdfOperations.protectInPlace(out, password);
                }
            } else {
                Bitmap source = bitmap;
                if (target.equals("jpg") && bitmap.hasAlpha()) {
                    source = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
                    Canvas c = new Canvas(source);
                    c.drawColor(Color.WHITE);
                    c.drawBitmap(bitmap, 0, 0, null);
                }
                Bitmap.CompressFormat format = target.equals("jpg") ? Bitmap.CompressFormat.JPEG : target.equals("png") ? Bitmap.CompressFormat.PNG : Bitmap.CompressFormat.WEBP;
                try (FileOutputStream os = new FileOutputStream(out)) {
                    if (!source.compress(format, target.equals("png") ? 100 : quality, os))
                        throw new IOException("This format is unavailable");
                }
                if (source != bitmap) source.recycle();
                ImageTools.setDpi(out, outputDpi);
                if (getPreferences(0).getBoolean("preserve_metadata", true)) {
                    ImageTools.copyExif(getContentResolver(), input.uri, out);
                }
            }
        } catch (Exception e) {
            out.delete();
            throw e;
        } finally {
            bitmap.recycle();
        }
        String mime = target.equals("pdf") ? "application/pdf" : "image/" + (target.equals("jpg") ? "jpeg" : target);
        return new OutputFile(out, outName, mime, target);
    }

    private void writePdf(Bitmap bitmap, File file) throws IOException {
        writePdf(Collections.singletonList(bitmap), file);
    }

    private void writePdf(List<Bitmap> bitmaps, File file) throws IOException {
        PdfDocument pdf = new PdfDocument();
        try {
            int number = 0;
            for (Bitmap bitmap : bitmaps) {
                PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(bitmap.getWidth(), bitmap.getHeight(), ++number).create());
                Canvas canvas = page.getCanvas();
                canvas.drawColor(Color.WHITE);
                canvas.drawBitmap(bitmap, null, new Rect(0, 0, bitmap.getWidth(), bitmap.getHeight()), new Paint(Paint.FILTER_BITMAP_FLAG));
                pdf.finishPage(page);
            }
            try (FileOutputStream output = new FileOutputStream(file)) {
                pdf.writeTo(output);
            }
        } finally {
            pdf.close();
        }
    }

    private String getHumanReadableErrorMessage(Throwable e, String defaultMsg) {
        if (e == null) return defaultMsg != null ? defaultMsg : "Operation failed";
        String msg = e.getMessage() != null ? e.getMessage().trim() : "";
        String lower = msg.toLowerCase(Locale.ROOT);
        String exType = e.getClass().getName();

        if (e instanceof InterruptedException || e instanceof java.io.InterruptedIOException || lower.contains("interrupted")) {
            return "The operation was interrupted. Please try again.";
        }
        if (lower.contains("cancelled") || lower.contains("canceled")) {
            return "Operation cancelled by user.";
        }
        if (e instanceof com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
                || lower.contains("password")
                || lower.contains("encrypted")
                || lower.contains("protect")) {
            if (lower.contains("incorrect") || lower.contains("wrong")) {
                return "Incorrect PDF password. Please verify the password and try again.";
            }
            return "This PDF is password-protected. Please unlock it first or enter the password.";
        }
        if (e instanceof SecurityException || lower.contains("permission") || lower.contains("access denied") || (e instanceof java.io.FileNotFoundException && lower.contains("denied"))) {
            return "File access denied. Please grant the required file permissions.";
        }
        if (lower.contains("enospc") || lower.contains("no space left") || lower.contains("insufficient storage")) {
            return "Insufficient storage space on device. Please free up space and try again.";
        }
        if (lower.contains("storage is unavailable") || lower.contains("could not prepare output") || lower.contains("could not prepare zip") || lower.contains("could not prepare files")) {
            return "Storage is unavailable. Please check that storage is accessible and try again.";
        }
        if (e instanceof OutOfMemoryError || lower.contains("out of memory") || lower.contains("too large") || lower.contains("limit") || lower.contains("megapixel")) {
            return !msg.isEmpty() && (msg.contains("limit") || msg.contains("large") || msg.contains("pages")) ? msg : "File or output is too large for available memory. Choose a smaller file or lower quality.";
        }
        if (lower.contains("ocr") || lower.contains("text recognition") || exType.contains("MlKit") || lower.contains("recogniz") || lower.contains("salvageable text")) {
            return "Text recognition (OCR) failed. Ensure the image or document contains clear, readable text.";
        }
        if (lower.contains("not available") || lower.contains("not supported") || lower.contains("not implemented") || lower.contains("cannot decode that image format")) {
            return !msg.isEmpty() ? msg : "This conversion or tool operation is not supported.";
        }
        if (e instanceof java.util.zip.ZipException || e instanceof java.io.EOFException || lower.contains("corrupt") || lower.contains("invalid") || lower.contains("damaged") || lower.contains("unreadable") || lower.contains("unsafe") || lower.contains("could not be read") || lower.contains("missing its")) {
            return !msg.isEmpty() ? msg : "The file appears to be corrupted or in an invalid format.";
        }
        if (!msg.isEmpty() && msg.length() < 120 && !msg.contains("java.") && !msg.contains("Exception") && !msg.contains("at com.")) {
            return msg;
        }
        return defaultMsg != null ? defaultMsg : "Operation failed. Please check the file and try again.";
    }

    private String stripUuidPrefix(String name) {
        if (name == null) return "";
        return name.replaceFirst("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-", "").replaceFirst("^[0-9a-fA-F-]{36}-", "");
    }

    private String safeName(String name) {
        if (name == null) return "";
        String s = name.trim();
        s = s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
        s = s.replaceAll("^[._\\s]+", "");
        s = s.replaceAll("[._\\s]+$", "");
        return s.trim();
    }

    private String ensureExtension(String name, String ext) {
        if (name == null || name.trim().isEmpty()) return "";
        String clean = safeName(name.trim());
        if (clean.isEmpty()) clean = "file";
        if (clean.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            clean = clean.substring(0, clean.length() - 4).trim();
            if (clean.isEmpty()) clean = "file";
        }
        if (ext == null || ext.trim().isEmpty()) return clean;
        String rawExt = ext.trim().startsWith(".") ? ext.trim().substring(1) : ext.trim();
        if (rawExt.isEmpty() || rawExt.equalsIgnoreCase("bin")) return clean;

        String dotExt = "." + rawExt.toLowerCase(Locale.ROOT);
        if (clean.toLowerCase(Locale.ROOT).endsWith(dotExt)) {
            return clean;
        }

        int dotIndex = clean.lastIndexOf('.');
        if (dotIndex > 0 && dotIndex < clean.length() - 1) {
            String existingExt = clean.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
            if (existingExt.length() <= 5 && !existingExt.contains(" ")) {
                clean = clean.substring(0, dotIndex).trim();
                if (clean.isEmpty()) clean = "file";
            }
        }

        return clean + "." + rawExt;
    }

    private String getFileExtension(String filename, String mime, String format) {
        if (filename != null && filename.contains(".")) {
            int dot = filename.lastIndexOf('.');
            if (dot < filename.length() - 1) {
                String ext = filename.substring(dot).toLowerCase(Locale.ROOT);
                if (!ext.equals(".bin")) {
                    return ext;
                }
            }
        }
        if (format != null && !format.isEmpty()) {
            String f = format.toLowerCase(Locale.ROOT);
            if (f.equals("searchable-pdf") || f.equals("searchable pdf") || f.equals("pdf")) return ".pdf";
            if (f.startsWith("ocr-docx") || f.equals("docx")) return ".docx";
            if (f.startsWith("ocr") || f.equals("txt")) return ".txt";
            if (f.equals("markdown") || f.equals("md")) return ".md";
            if (f.equals("html") || f.equals("htm")) return ".html";
            if (f.equals("png")) return ".png";
            if (f.equals("jpg") || f.equals("jpeg")) return ".jpg";
            if (f.equals("webp")) return ".webp";
            if (f.equals("gif")) return ".gif";
            if (f.equals("bmp")) return ".bmp";
            if (f.equals("xlsx") || f.equals("xls")) return ".xlsx";
            if (f.equals("pptx") || f.equals("ppt")) return ".pptx";
            if (f.equals("csv")) return ".csv";
            if (f.equals("rtf")) return ".rtf";
            if (f.equals("zip")) return ".zip";
            return "." + f;
        }
        if (mime != null) {
            String m = mime.toLowerCase(Locale.ROOT);
            if (m.contains("pdf")) return ".pdf";
            if (m.contains("png")) return ".png";
            if (m.contains("jpeg") || m.contains("jpg")) return ".jpg";
            if (m.contains("webp")) return ".webp";
            if (m.contains("gif")) return ".gif";
            if (m.contains("bmp")) return ".bmp";
            if (m.contains("wordprocessingml") || m.contains("docx")) return ".docx";
            if (m.contains("spreadsheetml") || m.contains("xlsx")) return ".xlsx";
            if (m.contains("presentationml") || m.contains("pptx")) return ".pptx";
            if (m.contains("zip")) return ".zip";
            if (m.contains("csv")) return ".csv";
            if (m.contains("html")) return ".html";
            if (m.contains("markdown")) return ".md";
            if (m.contains("text/plain") || m.contains("text/")) return ".txt";
            if (m.contains("rtf")) return ".rtf";
            if (m.contains("opendocument.text") || m.contains("odt")) return ".odt";
            if (m.contains("ms-excel") || m.contains("xls")) return ".xls";
        }
        return ".txt";
    }

    private String resolveMimeType(String filename, String mime, String format) {
        String ext = null;
        if (filename != null && filename.contains(".")) {
            int dot = filename.lastIndexOf('.');
            if (dot < filename.length() - 1) {
                ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (ext.equals("bin")) ext = null;
            }
        }
        if (ext == null && format != null && !format.isEmpty()) {
            String f = format.toLowerCase(Locale.ROOT);
            if (f.equals("searchable-pdf") || f.equals("searchable pdf")) ext = "pdf";
            else if (f.startsWith("ocr-docx")) ext = "docx";
            else if (f.startsWith("ocr")) ext = "txt";
            else if (f.equals("markdown")) ext = "md";
            else ext = f;
        }
        if (ext != null) {
            switch (ext) {
                case "pdf": return "application/pdf";
                case "docx": return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                case "doc": return "application/msword";
                case "xlsx": return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                case "xls": return "application/vnd.ms-excel";
                case "pptx": return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                case "ppt": return "application/vnd.ms-powerpoint";
                case "odt": return "application/vnd.oasis.opendocument.text";
                case "ods": return "application/vnd.oasis.opendocument.spreadsheet";
                case "odp": return "application/vnd.oasis.opendocument.presentation";
                case "rtf": return "application/rtf";
                case "png": return "image/png";
                case "jpg":
                case "jpeg": return "image/jpeg";
                case "webp": return "image/webp";
                case "gif": return "image/gif";
                case "bmp": return "image/bmp";
                case "svg": return "image/svg+xml";
                case "txt": return "text/plain";
                case "csv": return "text/csv";
                case "html":
                case "htm": return "text/html";
                case "md": return "text/markdown";
                case "json": return "application/json";
                case "xml": return "application/xml";
                case "zip": return "application/zip";
                case "tar": return "application/x-tar";
                case "gz": return "application/gzip";
                case "tgz": return "application/x-gtar";
            }
            String sysMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (sysMime != null && !sysMime.isEmpty() && !sysMime.equalsIgnoreCase("application/octet-stream")) {
                return sysMime;
            }
        }
        if (mime != null && !mime.isEmpty() && !mime.equalsIgnoreCase("application/octet-stream") && !mime.equals("*/*") && !mime.equalsIgnoreCase("extracted")) {
            return mime;
        }
        return "text/plain";
    }

    private OutputFile pendingSave;

    private void saveOutput(OutputFile f) {
        LinearLayout layout = column();
        layout.setPadding(dp(20), dp(8), dp(20), dp(8));

        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setText(f.name);
        nameInput.selectAll();
        styleEditText(nameInput);
        nameInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        layout.addView(nameInput, lp(-1, 50));

        boolean isPdf = "pdf".equalsIgnoreCase(f.target) || "application/pdf".equalsIgnoreCase(f.mime) || (f.name != null && f.name.toLowerCase(Locale.ROOT).endsWith(".pdf"));

        EditText savePasswordInput = null;
        CheckBox saveProtectCheck = null;
        if (isPdf) {
            savePasswordInput = new EditText(this);
            savePasswordInput.setSingleLine(true);
            savePasswordInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            savePasswordInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
            savePasswordInput.setHint("Password protection (optional)");
            savePasswordInput.setTextSize(14);
            savePasswordInput.setPadding(dp(14), 0, dp(14), 0);
            styleEditText(savePasswordInput);
            LinearLayout.LayoutParams pwp = lp(-1, 50);
            pwp.topMargin = dp(8);
            layout.addView(savePasswordInput, pwp);

            saveProtectCheck = new CheckBox(this);
            saveProtectCheck.setText("Protect with password");
            saveProtectCheck.setTextColor(ink);
            saveProtectCheck.setTextSize(13);
            saveProtectCheck.setChecked(false);
            final EditText finalSavePw = savePasswordInput;
            saveProtectCheck.setOnCheckedChangeListener((btn, isChecked) -> {
                finalSavePw.setEnabled(isChecked);
                finalSavePw.setAlpha(isChecked ? 1.0f : 0.5f);
            });
            savePasswordInput.setEnabled(false);
            savePasswordInput.setAlpha(0.5f);
            LinearLayout.LayoutParams cbp = lp(-1, -2);
            cbp.topMargin = dp(4);
            layout.addView(saveProtectCheck, cbp);
        }

        final EditText fSavePwInput = savePasswordInput;
        final CheckBox fSaveProtectCheck = saveProtectCheck;

        dialogBuilder()
                .setTitle("Save as")
                .setView(layout)
                .setPositiveButton("Save", (d, w) -> {
                    String input = nameInput.getText().toString().trim();
                    String ext = getFileExtension(f.name, f.mime, f.target);
                    String chosen = input.isEmpty() ? f.name : ensureExtension(input, ext);
                    if (chosen.isEmpty()) chosen = f.name;
                    chosen = ensureExtension(chosen, ext);

                    if (fSaveProtectCheck != null && fSaveProtectCheck.isChecked() && fSavePwInput != null) {
                        String pw = fSavePwInput.getText().toString();
                        if (pw.length() < 4) {
                            toast("Password must be at least 4 characters");
                            return;
                        }
                        try {
                            PdfOperations.protectInPlace(f.file, pw);
                        } catch (Exception e) {
                            toast("Could not protect PDF: " + (e.getMessage() != null ? e.getMessage() : "Error"));
                            return;
                        }
                    }

                    pendingSave = f;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    String safeMime = resolveMimeType(chosen, f.mime, f.target);
                    i.setType(safeMime);
                    i.putExtra(Intent.EXTRA_TITLE, chosen);
                    try {
                        startActivityForResult(i, SAVE_FILE);
                    } catch (ActivityNotFoundException e) {
                        toast("No save location is available");
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void copyTo(OutputFile f, Uri uri) {
        try (InputStream in = new FileInputStream(f.file); OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
            if (out == null) throw new IOException();
            byte[] b = new byte[32768];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            runOnUiThread(() -> toast("File saved"));
        } catch (Exception e) {
            runOnUiThread(() -> toast("Could not save file"));
        }
    }

    private File getAccessibleFileSwitchDir() {
        File targetDir = null;
        try {
            File docs = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
            if (docs != null) {
                targetDir = new File(docs, "FileSwitch");
                if (!targetDir.exists()) targetDir.mkdirs();
                if (targetDir.exists() && targetDir.canWrite()) return targetDir;
            }
        } catch (Exception ignored) {}

        try {
            File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            if (downloads != null) {
                targetDir = new File(downloads, "FileSwitch");
                if (!targetDir.exists()) targetDir.mkdirs();
                if (targetDir.exists() && targetDir.canWrite()) return targetDir;
            }
        } catch (Exception ignored) {}

        try {
            File ext = new File(Environment.getExternalStorageDirectory(), "FileSwitch");
            if (!ext.exists()) ext.mkdirs();
            if (ext.exists() && ext.canWrite()) return ext;
        } catch (Exception ignored) {}

        File appExt = getExternalFilesDir("FileSwitch");
        if (appExt != null && (appExt.exists() || appExt.mkdirs())) return appExt;

        File cacheDir = new File(getCacheDir(), "fileswitch");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        return cacheDir;
    }

    private File getUniqueDestinationFile(File targetDir, String desiredName) {
        if (desiredName == null || desiredName.trim().isEmpty()) {
            desiredName = "FileSwitch_output";
        }
        desiredName = safeName(desiredName);
        if (desiredName.isEmpty()) desiredName = "FileSwitch_output";

        String nameOnly = desiredName;
        String ext = "";
        int dotIndex = desiredName.lastIndexOf('.');
        if (dotIndex > 0) {
            nameOnly = desiredName.substring(0, dotIndex);
            ext = desiredName.substring(dotIndex);
        }

        File dest = new File(targetDir, desiredName);
        int count = 1;
        while (dest.exists()) {
            dest = new File(targetDir, nameOnly + " (" + count + ")" + ext);
            count++;
        }
        return dest;
    }

    private OutputFile saveOutputFileToFileSwitchDir(OutputFile output) {
        if (output == null || output.file == null || !output.file.exists() || output.file.length() <= 0) {
            return null;
        }
        File destFile = null;
        try {
            File targetDir = getAccessibleFileSwitchDir();
            destFile = getUniqueDestinationFile(targetDir, output.name);

            if (!output.file.getAbsolutePath().equals(destFile.getAbsolutePath())) {
                try (InputStream in = new FileInputStream(output.file);
                     OutputStream out = new FileOutputStream(destFile)) {
                    byte[] buf = new byte[32768];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    out.flush();
                }

                try {
                    output.file.delete();
                } catch (Exception ignored) {}
            }

            if (!destFile.exists() || destFile.length() <= 0) {
                if (destFile.exists()) destFile.delete();
                return null;
            }

            try {
                MediaScannerConnection.scanFile(this, new String[]{destFile.getAbsolutePath()}, new String[]{output.mime}, null);
            } catch (Exception ignored) {}

            OutputFile saved = new OutputFile(destFile, destFile.getName(), output.mime, output.target);
            saved.sourceSize = output.sourceSize;
            return saved;
        } catch (Exception e) {
            if (destFile != null && destFile.exists()) {
                try { destFile.delete(); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    private Uri outputUri(OutputFile f) {
        return Uri.parse("content://" + getPackageName() + ".fileprovider/" + Uri.encode(f.file.getName()));
    }

    private void shareOutput(OutputFile f) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(f.mime);
        i.putExtra(Intent.EXTRA_STREAM, outputUri(f));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Share file"));
    }

    private void openOutput(OutputFile f) {
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(outputUri(f), f.mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(i, "Open file with"));
        } catch (ActivityNotFoundException e) {
            toast("No app can open this file type");
        }
    }

    private void renderTools() {
        heading("Tools", "Find an image conversion, document, or PDF action.");
        EditText search = new EditText(this);
        search.setSingleLine(true);
        search.setHint("Search tools");
        search.setTextSize(15);
        search.setPadding(dp(14), 0, dp(14), 0);
        styleEditText(search);
        body.addView(search, lp(-1, 52));
        LinearLayout list = column();
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(14);
        body.addView(list, p);
        Runnable refresh = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            list.removeAllViews();
            int count = 0;
            if (q.isEmpty() || "image conversion".contains(q) || "jpg png webp bmp image convert exif contact sheet".contains(q)) {
                addToolCard(list, "Image tools", "Convert images, edit EXIF metadata, or generate contact sheets.", null);
                count++;
            }
            if (q.isEmpty() || "document".contains(q) || "word".contains(q) || "spreadsheet".contains(q) || "xlsx".contains(q) || "docx".contains(q) || "text".contains(q) || "csv".contains(q) || "repair".contains(q)) {
                addToolCard(list, "Document tools", "Convert DOCX, XLSX, CSV, TXT, Markdown, HTML, RTF and repair damaged documents.", "document");
                count++;
            }
            if (q.isEmpty() || "pdf".contains(q) || "image to pdf create document".contains(q) || "searchable".contains(q) || "ocr".contains(q)) {
                addToolCard(list, "PDF & OCR tools", "Create a PDF from images, export PDF pages, or create searchable PDFs.", "pdf");
                count++;
            }
            String[][] pdfTools = {
                {"Merge PDFs", "merge"},
                {"Split into pages", "split"},
                {"Extract pages", "extract"},
                {"Delete pages", "delete"},
                {"Reorder pages", "reorder"},
                {"Rotate pages", "rotate"},
                {"Resize pages", "resize"},
                {"Insert image", "addImage"},
                {"Compress PDF", "compress"},
                {"Add watermark & page numbers", "annotate"},
                {"Stamp PDF (CONFIDENTIAL, APPROVED...)", "stamp"},
                {"Convert to PDF/A (Archival)", "pdfa"},
                {"Repair Corrupted PDF", "repairPdf"},
                {"Fill Form Fields", "fillForm"},
                {"Digital Signature Stamp", "sign"},
                {"Edit PDF Metadata", "editPdfMeta"},
                {"Crop page margins", "crop"},
                {"Protect with password", "protect"},
                {"Remove password", "unlock"},
                {"PDF details", "metadata"}
            };
            boolean showPdf = q.isEmpty() || "pdf".contains(q);
            for (String[] tool : pdfTools) if (tool[0].toLowerCase(Locale.ROOT).contains(q)) showPdf = true;
            boolean addedPdfButtons = false;
            if (showPdf) {
                for (String[] tool : pdfTools)
                    if (tool[0].toLowerCase(Locale.ROOT).contains(q) || ("pdf " + tool[0].toLowerCase(Locale.ROOT)).contains(q)) {
                        addPdfOperationButton(list, tool[0], tool[1]);
                        count++;
                        addedPdfButtons = true;
                    }
            }
            if (q.isEmpty() || "zip".contains(q) || "archive".contains(q) || "tar".contains(q) || "gz".contains(q)) {
                if (addedPdfButtons) {
                    View spacer = new View(this);
                    list.addView(spacer, lp(-1, 14));
                }
                addToolCard(list, "Archive tools", "Create a ZIP from files or extract an existing archive (ZIP, TAR, GZ, TGZ).", "zip");
                count++;
            }
            if (count == 0) list.addView(text("No matching tools", 14, false, muted));
        };
        refresh.run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                refresh.run();
            }
            public void afterTextChanged(android.text.Editable e) {}
        });
    }

    private void addToolCard(LinearLayout parent, String title, String description, String target) {
        LinearLayout c = column();
        c.addView(text(title, 17, true, ink));
        TextView d = text(description, 14, false, muted);
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(6);
        c.addView(d, p);
        if ("zip".equals(target)) {
            c.addView(button("Create ZIP from files", true, () -> openToolPage("zip_create")), layoutTop(10));
            c.addView(button("Extract Archive (ZIP, TAR, GZ, TGZ)", false, () -> openToolPage("zip_extract")), layoutTop(8));
        } else if ("image".equals(target) || target == null) {
            c.addView(button("Convert image formats", true, () -> openToolPage("image_convert")), layoutTop(10));
            c.addView(button("Edit Image EXIF Metadata", false, () -> openToolPage("image_exif")), layoutTop(8));
            c.addView(button("Create Contact Sheet", false, () -> openToolPage("image_contact_sheet")), layoutTop(8));
        } else if ("document".equals(target)) {
            c.addView(button("Convert Document Formats", true, () -> openToolPage("doc_convert")), layoutTop(10));
            c.addView(button("Repair Damaged Document", false, () -> openToolPage("doc_repair")), layoutTop(8));
        } else {
            c.addView(button("Create PDF from images", true, () -> openToolPage("pdf_create")), layoutTop(10));
            c.addView(button("Create Searchable PDF (OCR)", false, () -> openToolPage("pdf_searchable_ocr")), layoutTop(8));
            c.addView(button("OCR Text Extractor", false, () -> openToolPage("ocr_extract_text")), layoutTop(8));
        }
        card(parent, c);
    }

    private void addPdfOperationButton(LinearLayout parent, String title, String operation) {
        TextView b = button(title, false, () -> openToolPage(operation));
        LinearLayout.LayoutParams p = lp(-1, 48);
        p.topMargin = dp(6);
        parent.addView(b, p);
    }

    private void openToolPage(String toolId) {
        activeToolId = toolId;
        toolSources.clear();
        toolResultFiles.clear();
        toolProcessing = false;
        toolStatusText = "";
        toolSecondaryUri = null;
        toolSecondaryBitmap = null;
        toolPdfPageCount = 0;
        toolPdfCounting = false;

        toolCompressLevel = 70;
        toolResizePercent = 75;
        toolResizePreset = -1;
        toolPassword = "";
        toolAllowPrint = true;
        toolAllowCopy = true;
        toolAllowEdit = true;
        toolWatermarkText = "";
        toolStampText = "CONFIDENTIAL";
        toolStampPos = 0;
        toolFormValues = "";
        toolFlattenForm = true;
        toolSignerName = "";
        toolSignPage = 0;
        toolMetaTitle = "";
        toolMetaAuthor = "";
        toolMetaSubject = "";
        toolMetaKeywords = "";
        toolCropMargin = "12";
        toolPageRange = "";
        toolSelectedPages.clear();
        toolReorderedPages.clear();
        toolRotateAngle = 90;
        toolCustomOutputName = "";
        toolExifDesc = "";
        toolExifArtist = "";
        toolExifCopyright = "";
        toolExifMake = "";
        toolExifModel = "";
        toolExifScrubGps = false;
        toolContactSheetCols = 3;
        toolImageTarget = "jpg";
        toolImageQuality = 90;
        toolImageDpi = 96;
        toolDocTarget = "pdf";
        toolOcrLang = "latin";
        toolOcrTarget = "txt";

        showPage("ToolPage");
    }

    private void openToolPicker(String toolId) {
        if (toolProcessing) {
            toast("Operation is currently in progress");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        boolean multi = isToolMultiFile(toolId);
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, multi);

        if ("image_convert".equals(toolId) || "image_exif".equals(toolId) || "image_contact_sheet".equals(toolId) || "pdf_create".equals(toolId)) {
            picker.setType("image/*");
        } else if ("ocr_extract_text".equals(toolId)) {
            picker.setType("*/*");
            picker.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf"});
        } else if ("doc_convert".equals(toolId) || "doc_repair".equals(toolId) || "zip_create".equals(toolId) || "zip_extract".equals(toolId)) {
            picker.setType("*/*");
        } else {
            picker.setType("application/pdf");
        }

        try {
            startActivityForResult(picker, PICK_TOOL_FILE);
        } catch (ActivityNotFoundException e) {
            toast("No file picker is available");
        }
    }

    private void openToolSecondaryPicker() {
        if (toolProcessing) {
            toast("Operation is currently in progress");
            return;
        }
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("image/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try {
            startActivityForResult(picker, PICK_TOOL_SECONDARY_FILE);
        } catch (ActivityNotFoundException e) {
            toast("No image picker is available");
        }
    }

    private void onToolFilesSelected(ArrayList<Uri> picked) {
        if (picked == null || picked.isEmpty()) return;
        ArrayList<Uri> valid = new ArrayList<>();
        ArrayList<String> rejected = new ArrayList<>();

        for (Uri u : picked) {
            String name = displayName(u);
            String type = detectType(u, name);
            if (querySize(u) > MAX_FILE) {
                rejected.add(name + ": exceeds 100 MB limit");
                continue;
            }
            if (isPdfTool(activeToolId) && !"pdf".equalsIgnoreCase(type)) {
                rejected.add(name + ": not a PDF file");
                continue;
            }
            if (activeToolId != null && activeToolId.startsWith("image") && !Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg").contains(type)) {
                rejected.add(name + ": not a supported image format");
                continue;
            }
            valid.add(u);
        }

        if (valid.isEmpty()) {
            if (!rejected.isEmpty()) {
                toast("Invalid file: " + rejected.get(0));
            }
            return;
        }

        if (!rejected.isEmpty()) {
            toast("Added " + valid.size() + " valid file(s). " + rejected.get(0));
        }

        if (isToolMultiFile(activeToolId)) {
            for (Uri u : valid) {
                if (!toolSources.contains(u)) {
                    toolSources.add(u);
                }
            }
        } else {
            toolSources.clear();
            toolSources.add(valid.get(0));
        }
        toolResultFiles.clear();
        toolProcessing = false;
        toolStatusText = "";

        if (!toolSources.isEmpty()) {
            Uri primary = toolSources.get(0);
            if (isPdfTool(activeToolId)) {
                toolPdfCounting = true;
                worker.execute(() -> {
                    try {
                        int count = PdfOperations.pageCount(getContentResolver(), primary);
                        toolPdfPageCount = count;
                        if ("editPdfMeta".equals(activeToolId) || "metadata".equals(activeToolId)) {
                            try (InputStream in = getContentResolver().openInputStream(primary);
                                 PDDocument doc = PDDocument.load(in)) {
                                com.tom_roush.pdfbox.pdmodel.PDDocumentInformation info = doc.getDocumentInformation();
                                if (info != null) {
                                    toolMetaTitle = info.getTitle() != null ? info.getTitle() : "";
                                    toolMetaAuthor = info.getAuthor() != null ? info.getAuthor() : "";
                                    toolMetaSubject = info.getSubject() != null ? info.getSubject() : "";
                                    toolMetaKeywords = info.getKeywords() != null ? info.getKeywords() : "";
                                }
                            } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {
                        toolPdfPageCount = 1;
                    } finally {
                        toolPdfCounting = false;
                        runOnUiThread(this::renderToolPage);
                    }
                });
            } else if ("image_exif".equals(activeToolId)) {
                worker.execute(() -> {
                    try (InputStream in = getContentResolver().openInputStream(primary)) {
                        if (Build.VERSION.SDK_INT >= 24 && in != null) {
                            android.media.ExifInterface exif = new android.media.ExifInterface(in);
                            toolExifDesc = exif.getAttribute(android.media.ExifInterface.TAG_IMAGE_DESCRIPTION);
                            if (toolExifDesc == null) toolExifDesc = "";
                            toolExifArtist = exif.getAttribute(android.media.ExifInterface.TAG_ARTIST);
                            if (toolExifArtist == null) toolExifArtist = "";
                            toolExifCopyright = exif.getAttribute(android.media.ExifInterface.TAG_COPYRIGHT);
                            if (toolExifCopyright == null) toolExifCopyright = "";
                            toolExifMake = exif.getAttribute(android.media.ExifInterface.TAG_MAKE);
                            if (toolExifMake == null) toolExifMake = "";
                            toolExifModel = exif.getAttribute(android.media.ExifInterface.TAG_MODEL);
                            if (toolExifModel == null) toolExifModel = "";
                        }
                    } catch (Exception ignored) {}
                    runOnUiThread(this::renderToolPage);
                });
            }
        }
        renderToolPage();
    }

    private boolean isPdfTool(String id) {
        if (id == null) return false;
        return Arrays.asList(
            "merge", "split", "extract", "delete", "reorder", "rotate", "resize",
            "addImage", "compress", "annotate", "stamp", "pdfa", "repairPdf",
            "fillForm", "sign", "editPdfMeta", "crop", "protect", "unlock",
            "metadata", "pdf_searchable_ocr"
        ).contains(id);
    }

    private boolean isToolMultiFile(String id) {
        if (id == null) return false;
        return Arrays.asList("merge", "zip_create", "image_contact_sheet", "pdf_create", "image_convert", "doc_convert", "ocr_extract_text").contains(id);
    }

    private String getToolTitle(String id) {
        if (id == null) return "Tool";
        switch (id) {
            case "merge": return "Merge PDFs";
            case "split": return "Split PDF into Pages";
            case "extract": return "Extract PDF Pages";
            case "delete": return "Delete PDF Pages";
            case "reorder": return "Reorder PDF Pages";
            case "rotate": return "Rotate PDF Pages";
            case "resize": return "Resize PDF Pages";
            case "addImage": return "Insert Image into PDF";
            case "compress": return "Compress PDF";
            case "annotate": return "Watermark & Page Numbers";
            case "stamp": return "Stamp PDF Document";
            case "pdfa": return "Convert to PDF/A";
            case "repairPdf": return "Repair Corrupted PDF";
            case "fillForm": return "Fill Form Fields";
            case "sign": return "Digital Signature Stamp";
            case "editPdfMeta": return "Edit PDF Metadata";
            case "crop": return "Crop Page Margins";
            case "protect": return "Protect PDF with Password";
            case "unlock": return "Remove PDF Password";
            case "metadata": return "PDF Details & Inspection";
            case "image_convert": return "Image Format Converter";
            case "image_exif": return "Edit EXIF Metadata";
            case "image_contact_sheet": return "Create Contact Sheet";
            case "doc_convert": return "Document Converter";
            case "doc_repair": return "Repair Corrupted Document";
            case "pdf_create": return "Create PDF from Images";
            case "pdf_searchable_ocr": return "Searchable PDF (OCR)";
            case "ocr_extract_text": return "OCR Text Extractor";
            case "zip_create": return "Create ZIP Archive";
            case "zip_extract": return "Extract Archive";
            default: return "File Tool";
        }
    }

    private String getToolCategory(String id) {
        if (id == null) return "UTILITY";
        if (id.startsWith("image_")) return "IMAGE TOOL";
        if (id.startsWith("doc_")) return "DOCUMENT TOOL";
        if (id.startsWith("zip_")) return "ARCHIVE TOOL";
        if (id.contains("ocr")) return "OCR TOOL";
        return "PDF TOOL";
    }

    private String getToolDescription(String id) {
        if (id == null) return "";
        switch (id) {
            case "merge": return "Combine multiple PDF documents into a single file in your desired order.";
            case "split": return "Extract all pages of a PDF into individual single-page documents.";
            case "extract": return "Extract specific pages or page ranges (e.g. 1-3, 5) into a new PDF.";
            case "delete": return "Remove unwanted pages or page ranges from a PDF document.";
            case "reorder": return "Rearrange PDF pages into a custom sequence.";
            case "rotate": return "Rotate PDF pages 90°, 180°, or 270° clockwise.";
            case "resize": return "Standardize page dimensions to A4, Letter, Legal, or scale by percentage.";
            case "addImage": return "Overlay or stamp an image graphic onto a specific page of your PDF.";
            case "compress": return "Reduce PDF file size by optimizing embedded images and streams.";
            case "annotate": return "Add custom text watermark, headers, footers, or page numbering.";
            case "stamp": return "Apply official stamp banners (CONFIDENTIAL, APPROVED, DRAFT, FINAL).";
            case "pdfa": return "Convert document to PDF/A-1b archival standard for long-term storage.";
            case "repairPdf": return "Rebuild damaged PDF objects, cross-reference tables, and streams.";
            case "fillForm": return "Inspect and fill interactive AcroForm text fields in a PDF.";
            case "sign": return "Add a visual signature stamp badge with signer name and timestamp.";
            case "editPdfMeta": return "View and modify Title, Author, Subject, and Keywords metadata.";
            case "crop": return "Trim whitespace or custom margin widths from all pages.";
            case "protect": return "Encrypt PDF with 128-bit / 256-bit AES password protection.";
            case "unlock": return "Decrypt password-protected PDF to generate an unrestricted copy.";
            case "metadata": return "Inspect page count, PDF version, security restrictions, and details.";
            case "image_convert": return "Convert images between JPG, PNG, WEBP, BMP, GIF, HEIC, and SVG.";
            case "image_exif": return "View, edit, or remove camera and GPS metadata from photos.";
            case "image_contact_sheet": return "Generate a grid contact sheet containing photo thumbnails with labels.";
            case "doc_convert": return "Convert DOCX, XLSX, CSV, TXT, Markdown, HTML, RTF documents.";
            case "doc_repair": return "Recover text and formatting from damaged Word, Excel, or text files.";
            case "pdf_create": return "Combine multiple photos and scans into a single multi-page PDF.";
            case "pdf_searchable_ocr": return "Perform optical character recognition on scanned pages to make text selectable.";
            case "ocr_extract_text": return "Extract plain text from scanned documents and images using on-device ML.";
            case "zip_create": return "Compress multiple files into a standard ZIP archive with optional naming.";
            case "zip_extract": return "Extract contents of ZIP, TAR, GZ, or TGZ archives directly to device.";
            default: return "Perform offline file operation safely on your device.";
        }
    }

    private String getToolActionLabel(String id) {
        if (id == null) return "Apply Tool";
        switch (id) {
            case "merge": return "Merge PDFs";
            case "split": return "Split PDF into Pages";
            case "extract": return "Extract Pages";
            case "delete": return "Delete Pages";
            case "reorder": return "Reorder Pages";
            case "rotate": return "Rotate Pages";
            case "resize": return "Resize Pages";
            case "addImage": return "Insert Image into PDF";
            case "compress": return "Compress PDF";
            case "annotate": return "Apply Watermark";
            case "stamp": return "Apply Stamp";
            case "pdfa": return "Convert to PDF/A";
            case "repairPdf": return "Repair PDF";
            case "fillForm": return "Fill Form Fields";
            case "sign": return "Apply Digital Signature";
            case "editPdfMeta": return "Save PDF Metadata";
            case "crop": return "Crop PDF Margins";
            case "protect": return "Protect PDF";
            case "unlock": return "Remove Password";
            case "metadata": return "Inspect PDF Details";
            case "image_convert": return "Convert Images";
            case "image_exif": return "Save EXIF Metadata";
            case "image_contact_sheet": return "Generate Contact Sheet";
            case "doc_convert": return "Convert Documents";
            case "doc_repair": return "Repair Document";
            case "pdf_create": return "Create PDF";
            case "pdf_searchable_ocr": return "Create Searchable PDF";
            case "ocr_extract_text": return "Extract Text with OCR";
            case "zip_create": return "Create ZIP Archive";
            case "zip_extract": return "Extract Archive";
            default: return "Process File";
        }
    }

    private TextView chip(String label, boolean selected, Runnable onClick) {
        TextView t = text(label, 13, selected, selected ? Color.WHITE : ink);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(44));
        t.setMinWidth(dp(44));
        t.setBackground(shape(selected ? Color.parseColor("#245CCB") : surface, 12, selected ? 0 : line));
        pad(t, 12, 8, 12, 8);
        t.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.96f).scaleY(.96f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        t.setOnClickListener(v -> {
            if (isClickDebounced()) return;
            if (onClick != null) onClick.run();
        });
        return t;
    }

    private void renderToolPage() {
        if (body == null) return;
        body.removeAllViews();

        if (activeToolId == null) {
            heading("Tool", "Select a tool from the tools menu.");
            body.addView(button("Go to Tools Menu", true, () -> showPage("Tools")), lp(-1, 52));
            return;
        }

        // 1. Result State (if completed)
        if (!toolResultFiles.isEmpty()) {
            LinearLayout resultBox = column();
            pad(resultBox, 18, 18, 18, 18);
            resultBox.setBackground(shape(surface, 20, Color.parseColor("#10B981")));

            LinearLayout badgeRow = row();
            TextView checkIcon = text("✓", 16, true, Color.WHITE);
            checkIcon.setGravity(Gravity.CENTER);
            checkIcon.setBackground(shape(Color.parseColor("#10B981"), 12, 0));
            badgeRow.addView(checkIcon, lp(28, 28));

            TextView resTitle = text(toolResultFiles.size() == 1 ? "File saved successfully" : "Files saved successfully", 18, true, ink);
            LinearLayout.LayoutParams rtp = lp(-2, -2);
            rtp.leftMargin = dp(10);
            badgeRow.addView(resTitle, rtp);
            resultBox.addView(badgeRow, lp(-1, -2));

            TextView countText = text("Saved to FileSwitch folder · " + toolResultFiles.size() + (toolResultFiles.size() == 1 ? " file ready" : " files ready"), 13, false, muted);
            LinearLayout.LayoutParams ctp = lp(-1, -2);
            ctp.topMargin = dp(6);
            ctp.bottomMargin = dp(14);
            resultBox.addView(countText, ctp);

            for (OutputFile f : toolResultFiles) {
                LinearLayout fileRow = column();
                pad(fileRow, 14, 12, 14, 12);
                fileRow.setBackground(shape(bg, 14, line));

                TextView nameView = text(f.name, 15, true, ink);
                nameView.setSingleLine(true);
                nameView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                fileRow.addView(nameView, lp(-1, -2));

                TextView sizeView = text(sizeLabel(f.file.length()) + " · " + (f.mime != null ? f.mime : f.target.toUpperCase(Locale.ROOT)), 12, false, muted);
                LinearLayout.LayoutParams szp = lp(-1, -2);
                szp.topMargin = dp(2);
                fileRow.addView(sizeView, szp);

                LinearLayout btnCol = column();
                LinearLayout r1 = row();
                r1.addView(button("Open File", true, () -> openOutput(f)), new LinearLayout.LayoutParams(0, dp(44), 1));
                View s1 = new View(this);
                r1.addView(s1, lp(8, 1));
                r1.addView(button("Share", false, () -> shareOutput(f)), new LinearLayout.LayoutParams(0, dp(44), 1));
                btnCol.addView(r1, lp(-1, -2));

                LinearLayout r2 = row();
                r2.addView(button("Save As", false, () -> saveOutput(f)), new LinearLayout.LayoutParams(0, dp(44), 1));
                View s2 = new View(this);
                r2.addView(s2, lp(8, 1));
                r2.addView(button("Done", false, () -> {
                    toolSources.clear();
                    toolResultFiles.clear();
                    toolStatusText = "";
                    showPage("Tools");
                }), new LinearLayout.LayoutParams(0, dp(44), 1));
                LinearLayout.LayoutParams r2p = lp(-1, -2);
                r2p.topMargin = dp(8);
                btnCol.addView(r2, r2p);

                LinearLayout.LayoutParams brp = lp(-1, -2);
                brp.topMargin = dp(12);
                fileRow.addView(btnCol, brp);

                LinearLayout.LayoutParams frp = lp(-1, -2);
                frp.bottomMargin = dp(10);
                resultBox.addView(fileRow, frp);
            }

            resultBox.addView(button("Process Another File", false, () -> {
                toolSources.clear();
                toolResultFiles.clear();
                toolStatusText = "";
                renderToolPage();
            }), layoutTop(4));

            card(body, resultBox);
        }

        // 2. Processing / Loading State
        if (toolProcessing) {
            LinearLayout progCard = column();
            pad(progCard, 20, 20, 20, 20);
            progCard.setBackground(shape(surface, 20, accent));
            progCard.setGravity(Gravity.CENTER_HORIZONTAL);

            FileCreationAnimationView toolAnim = new FileCreationAnimationView(this);
            toolAnim.setPalette(surface, ink, muted, accent, line);
            progCard.addView(toolAnim, lp(-1, 80));
            toolAnim.startConstruction();

            TextView st = text(toolStatusText.isEmpty() ? "Processing files offline..." : toolStatusText, 15, true, ink);
            st.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams stlp = lp(-1, -2);
            stlp.topMargin = dp(12);
            progCard.addView(st, stlp);

            TextView safeNotice = text("Your files remain 100% private on this device.", 12, false, muted);
            safeNotice.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams snp = lp(-1, -2);
            snp.topMargin = dp(4);
            progCard.addView(safeNotice, snp);

            card(body, progCard);
            return;
        }

        // 3. Error Banner (if any previous operation failed)
        if (toolStatusText != null && toolStatusText.startsWith("Error:")) {
            LinearLayout errCard = column();
            pad(errCard, 14, 14, 14, 14);
            int errBg = isDark() ? Color.parseColor("#3B1D1D") : Color.parseColor("#FEE2E2");
            int errStroke = Color.parseColor("#EF4444");
            int errTextColor = isDark() ? Color.parseColor("#FCA5A5") : Color.parseColor("#B91C1C");
            errCard.setBackground(shape(errBg, 14, errStroke));
            TextView errText = text("⚠ " + toolStatusText.substring(6).trim(), 13, true, errTextColor);
            errCard.addView(errText, lp(-1, -2));
            card(body, errCard);
        }

        // 4. Header Description Card
        LinearLayout infoCard = column();
        TextView descView = text(getToolDescription(activeToolId), 14, false, ink);
        infoCard.addView(descView, lp(-1, -2));
        card(body, infoCard);

        // 5. File Selection Section
        LinearLayout fileCard = column();
        LinearLayout fileHeader = row();
        fileHeader.addView(text("Choose File" + (isToolMultiFile(activeToolId) ? "s" : ""), 16, true, ink), new LinearLayout.LayoutParams(0, -2, 1));
        if (!toolSources.isEmpty()) {
            TextView clearBtn = text("Clear", 13, true, Color.parseColor("#EF4444"));
            clearBtn.setContentDescription("Clear all selected files");
            pad(clearBtn, 10, 6, 10, 6);
            clearBtn.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(.94f).scaleY(.94f).setDuration(80).start();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                return false;
            });
            clearBtn.setOnClickListener(v -> {
                if (isClickDebounced()) return;
                if (!toolProcessing) {
                    if (toolSources.size() > 1) {
                        dialogBuilder()
                                .setTitle("Clear selected files")
                                .setMessage("Remove all selected files from this tool?")
                                .setPositiveButton("Clear", (d, w) -> {
                                    toolSources.clear();
                                    toolResultFiles.clear();
                                    toolStatusText = "";
                                    renderToolPage();
                                })
                                .setNegativeButton("Cancel", null)
                                .show();
                    } else {
                        toolSources.clear();
                        toolResultFiles.clear();
                        toolStatusText = "";
                        renderToolPage();
                    }
                }
            });
            fileHeader.addView(clearBtn, lp(-2, -2));
        }
        fileCard.addView(fileHeader, lp(-1, -2));

        if (toolSources.isEmpty()) {
            String fileTypeDesc = isPdfTool(activeToolId) ? "PDF Document" : (activeToolId.startsWith("image") ? "Image(s)" : "File(s)");
            TextView hint = text("No file selected yet. Select a " + fileTypeDesc.toLowerCase(Locale.ROOT) + " to configure and process.", 13, false, muted);
            LinearLayout.LayoutParams hlp = lp(-1, -2);
            hlp.topMargin = dp(6);
            fileCard.addView(hint, hlp);

            String pickLabel = "+ Select " + fileTypeDesc;
            TextView pickBtn = button(pickLabel, true, () -> openToolPicker(activeToolId));
            fileCard.addView(pickBtn, layoutTop(12));
        } else {
            LinearLayout fileList = column();
            for (int i = 0; i < toolSources.size(); i++) {
                final int idx = i;
                Uri u = toolSources.get(i);
                LinearLayout item = row();
                pad(item, 12, 10, 12, 10);
                item.setBackground(shape(bg, 12, line));

                LinearLayout nameCol = column();
                TextView fn = text(displayName(u), 14, true, ink);
                fn.setSingleLine(true);
                fn.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                nameCol.addView(fn, lp(-1, -2));

                String detailsStr = sizeLabel(querySize(u));
                if (isPdfTool(activeToolId) && toolPdfPageCount > 0 && idx == 0) {
                    detailsStr += " · " + toolPdfPageCount + (toolPdfPageCount == 1 ? " page" : " pages");
                }
                TextView sz = text(detailsStr, 12, false, muted);
                nameCol.addView(sz, lp(-1, -2));

                item.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1));

                if (isToolMultiFile(activeToolId)) {
                    TextView rm = text("✕", 15, true, muted);
                    rm.setGravity(Gravity.CENTER);
                    rm.setContentDescription("Remove " + displayName(u));
                    rm.setBackground(shape(surface, 12, line));
                    rm.setOnTouchListener((v, event) -> {
                        if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                            v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
                        else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                            v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                        return false;
                    });
                    rm.setOnClickListener(v -> {
                        toolSources.remove(idx);
                        renderToolPage();
                    });
                    item.addView(rm, lp(44, 44));
                }

                LinearLayout.LayoutParams ilp = lp(-1, -2);
                ilp.topMargin = dp(8);
                fileList.addView(item, ilp);
            }
            fileCard.addView(fileList, lp(-1, -2));

            LinearLayout actionRow = row();
            if (isToolMultiFile(activeToolId)) {
                actionRow.addView(button("+ Add More", false, () -> openToolPicker(activeToolId)), new LinearLayout.LayoutParams(0, dp(44), 1));
                View sp = new View(this);
                actionRow.addView(sp, lp(8, 1));
            }
            actionRow.addView(button("Replace Files", false, () -> {
                toolSources.clear();
                openToolPicker(activeToolId);
            }), new LinearLayout.LayoutParams(0, dp(44), 1));
            fileCard.addView(actionRow, layoutTop(12));
        }
        card(body, fileCard);

        // 5. Tool-Specific Options Card
        if (!toolSources.isEmpty()) {
            LinearLayout optionsCard = column();
            optionsCard.addView(text("Tool Settings", 16, true, ink), lp(-1, -2));
            renderToolOptionsUI(optionsCard);

            if (!"metadata".equals(activeToolId) && !"zip_extract".equals(activeToolId)) {
                TextView nameLabel = text("Output File Name (optional):", 13, false, muted);
                optionsCard.addView(nameLabel, layoutTop(12));
                EditText nameInput = new EditText(this);
                nameInput.setSingleLine(true);
                nameInput.setHint(getDefaultToolOutputName(activeToolId));
                nameInput.setText(toolCustomOutputName);
                styleEditText(nameInput);
                nameInput.setPadding(dp(12), dp(10), dp(12), dp(10));
                nameInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolCustomOutputName = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                optionsCard.addView(nameInput, layoutTop(6));
            }

            card(body, optionsCard);

            // 6. Action CTA Button
            TextView cta = button(getToolActionLabel(activeToolId), true, this::executeActiveTool);
            body.addView(cta, layoutTop(8));
        }
    }

    private String getDefaultToolOutputName(String toolId) {
        String base = toolSources.isEmpty() ? "output" : safeName(displayName(toolSources.get(0))).replaceFirst("\\.[^.]*$", "");
        if (base.isEmpty()) base = "output";
        switch (toolId) {
            case "merge": return "merged.pdf";
            case "split": return base + "-page-1.pdf";
            case "extract": return base + "-extracted.pdf";
            case "delete": return base + "-modified.pdf";
            case "reorder": return base + "-reordered.pdf";
            case "rotate": return base + "-rotated.pdf";
            case "resize": return base + "-resized.pdf";
            case "addImage": return base + "-image.pdf";
            case "compress": return base + "-compressed.pdf";
            case "annotate": return base + "-watermarked.pdf";
            case "stamp": return base + "-stamped.pdf";
            case "pdfa": return base + "-pdfa.pdf";
            case "repairPdf": return "repaired-" + base + ".pdf";
            case "fillForm": return base + "-filled.pdf";
            case "sign": return base + "-signed.pdf";
            case "editPdfMeta": return base + "-metadata.pdf";
            case "crop": return base + "-cropped.pdf";
            case "protect": return base + "-protected.pdf";
            case "unlock": return base + "-unlocked.pdf";
            case "image_convert": return base + "." + toolImageTarget;
            case "image_exif": return base + ".jpg";
            case "image_contact_sheet": return "contact-sheet.png";
            case "doc_convert": return base + "." + toolDocTarget;
            case "doc_repair": return "repaired-" + base + ".docx";
            case "pdf_create": return "FileSwitch-images.pdf";
            case "pdf_searchable_ocr": return base + "-searchable.pdf";
            case "ocr_extract_text": return base + "-ocr.txt";
            case "zip_create": return "FileSwitch-archive.zip";
            default: return base + ".pdf";
        }
    }

    private void renderToolOptionsUI(LinearLayout container) {
        switch (activeToolId) {
            case "merge": {
                TextView hint = text("Drag or use controls to set merge order:", 13, false, muted);
                container.addView(hint, layoutTop(6));
                for (int i = 0; i < toolSources.size(); i++) {
                    final int idx = i;
                    Uri u = toolSources.get(i);
                    LinearLayout row = row();
                    pad(row, 10, 8, 10, 8);
                    row.setBackground(shape(bg, 10, line));
                    TextView num = text((idx + 1) + ". ", 14, true, accent);
                    row.addView(num, lp(-2, -2));
                    TextView name = text(displayName(u), 13, false, ink);
                    name.setSingleLine(true);
                    name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
                    row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
                    if (idx > 0) {
                        TextView up = text("▲", 14, true, ink);
                        up.setPadding(dp(8), dp(4), dp(8), dp(4));
                        up.setOnClickListener(v -> {
                            Uri temp = toolSources.get(idx);
                            toolSources.set(idx, toolSources.get(idx - 1));
                            toolSources.set(idx - 1, temp);
                            renderToolPage();
                        });
                        row.addView(up, lp(-2, -2));
                    }
                    if (idx < toolSources.size() - 1) {
                        TextView down = text("▼", 14, true, ink);
                        down.setPadding(dp(8), dp(4), dp(8), dp(4));
                        down.setOnClickListener(v -> {
                            Uri temp = toolSources.get(idx);
                            toolSources.set(idx, toolSources.get(idx + 1));
                            toolSources.set(idx + 1, temp);
                            renderToolPage();
                        });
                        row.addView(down, lp(-2, -2));
                    }
                    LinearLayout.LayoutParams lp = lp(-1, -2);
                    lp.topMargin = dp(6);
                    container.addView(row, lp);
                }
                break;
            }
            case "split": {
                TextView info = text("Extracts each page of the document into a standalone PDF file.", 13, false, muted);
                container.addView(info, layoutTop(6));
                break;
            }
            case "extract":
            case "delete":
            case "reorder": {
                String verb = "extract".equals(activeToolId) ? "keep" : ("delete".equals(activeToolId) ? "delete" : "reorder");
                TextView label = text("Enter page numbers to " + verb + " (e.g. 1-3, 5):", 13, false, muted);
                container.addView(label, layoutTop(6));

                EditText rangeInput = new EditText(this);
                rangeInput.setHint("e.g. 1-3, 5");
                rangeInput.setText(toolPageRange);
                rangeInput.setTextSize(15);
                rangeInput.setPadding(dp(12), dp(10), dp(12), dp(10));
                styleEditText(rangeInput);
                rangeInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        toolPageRange = s.toString();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(rangeInput, layoutTop(8));

                LinearLayout chipRow = row();
                chipRow.addView(chip("All Pages", false, () -> {
                    toolPageRange = "1-" + (toolPdfPageCount > 0 ? toolPdfPageCount : 10);
                    renderToolPage();
                }));
                View s1 = new View(this); chipRow.addView(s1, lp(8, 1));
                chipRow.addView(chip("Odd Pages", false, () -> {
                    StringBuilder sb = new StringBuilder();
                    int max = toolPdfPageCount > 0 ? toolPdfPageCount : 10;
                    for (int i = 1; i <= max; i += 2) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(i);
                    }
                    toolPageRange = sb.toString();
                    renderToolPage();
                }));
                View s2 = new View(this); chipRow.addView(s2, lp(8, 1));
                chipRow.addView(chip("Even Pages", false, () -> {
                    StringBuilder sb = new StringBuilder();
                    int max = toolPdfPageCount > 0 ? toolPdfPageCount : 10;
                    for (int i = 2; i <= max; i += 2) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(i);
                    }
                    toolPageRange = sb.toString();
                    renderToolPage();
                }));
                container.addView(chipRow, layoutTop(10));
                break;
            }
            case "rotate": {
                TextView label = text("Select rotation angle:", 13, false, muted);
                container.addView(label, layoutTop(6));
                LinearLayout chipRow = row();
                chipRow.addView(chip("90° Clockwise", toolRotateAngle == 90, () -> {
                    toolRotateAngle = 90;
                    renderToolPage();
                }), new LinearLayout.LayoutParams(0, -2, 1));
                View s1 = new View(this); chipRow.addView(s1, lp(6, 1));
                chipRow.addView(chip("180° Half Turn", toolRotateAngle == 180, () -> {
                    toolRotateAngle = 180;
                    renderToolPage();
                }), new LinearLayout.LayoutParams(0, -2, 1));
                View s2 = new View(this); chipRow.addView(s2, lp(6, 1));
                chipRow.addView(chip("270° Counter", toolRotateAngle == 270, () -> {
                    toolRotateAngle = 270;
                    renderToolPage();
                }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(chipRow, layoutTop(8));
                break;
            }
            case "resize": {
                TextView label = text("Choose standard paper size or custom scale:", 13, false, muted);
                container.addView(label, layoutTop(6));
                LinearLayout row1 = row();
                row1.addView(chip("A4 Standard", toolResizePreset == 0, () -> { toolResizePreset = 0; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s1 = new View(this); row1.addView(s1, lp(6, 1));
                row1.addView(chip("Letter", toolResizePreset == 1, () -> { toolResizePreset = 1; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s2 = new View(this); row1.addView(s2, lp(6, 1));
                row1.addView(chip("Legal", toolResizePreset == 2, () -> { toolResizePreset = 2; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(row1, layoutTop(8));

                LinearLayout row2 = row();
                row2.addView(chip("75% Scale", toolResizePreset == -1 && toolResizePercent == 75, () -> { toolResizePreset = -1; toolResizePercent = 75; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s3 = new View(this); row2.addView(s3, lp(6, 1));
                row2.addView(chip("50% Scale", toolResizePreset == -1 && toolResizePercent == 50, () -> { toolResizePreset = -1; toolResizePercent = 50; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s4 = new View(this); row2.addView(s4, lp(6, 1));
                row2.addView(chip("150% Scale", toolResizePreset == -1 && toolResizePercent == 150, () -> { toolResizePreset = -1; toolResizePercent = 150; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(row2, layoutTop(6));
                break;
            }
            case "addImage": {
                TextView label = text("Choose image graphic to insert:", 13, false, muted);
                container.addView(label, layoutTop(6));
                String imgName = toolSecondaryUri != null ? "✓ " + displayName(toolSecondaryUri) : "+ Pick Image to Insert";
                container.addView(button(imgName, toolSecondaryUri != null, this::openToolSecondaryPicker), layoutTop(8));

                TextView pageLabel = text("Target page number (1 to " + (toolPdfPageCount > 0 ? toolPdfPageCount : 1) + "):", 13, false, muted);
                container.addView(pageLabel, layoutTop(12));
                EditText pgInput = new EditText(this);
                pgInput.setText(String.valueOf(toolSignPage + 1));
                pgInput.setInputType(InputType.TYPE_CLASS_NUMBER);
                styleEditText(pgInput);
                pgInput.setPadding(dp(12), dp(10), dp(12), dp(10));
                pgInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        try { toolSignPage = Math.max(0, Integer.parseInt(s.toString()) - 1); } catch (Exception ignored) {}
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(pgInput, layoutTop(6));

                if (toolSecondaryUri != null && !toolSources.isEmpty()) {
                    TextView previewCropBtn = button("Visual Placement, Crop & Preview", false, () -> {
                        worker.execute(() -> {
                            try {
                                Bitmap origBmp = toolSecondaryBitmap;
                                if (origBmp == null) {
                                    String imgType = detectType(toolSecondaryUri, displayName(toolSecondaryUri));
                                    origBmp = ImageTools.decode(getContentResolver(), toolSecondaryUri, imgType);
                                    toolSecondaryBitmap = origBmp;
                                }
                                final Bitmap bmpToUse = origBmp;
                                runOnUiThread(() -> showVisualAddImageDialog(toolSources, toolPdfPageCount > 0 ? toolPdfPageCount : 1, toolSecondaryUri, bmpToUse));
                            } catch (Exception e) {
                                runOnUiThread(() -> toast("Could not open preview: " + (e.getMessage() != null ? e.getMessage() : "Error")));
                            }
                        });
                    });
                    container.addView(previewCropBtn, layoutTop(12));
                }
                break;
            }
            case "compress": {
                TextView label = text("Compression Level:", 13, false, muted);
                container.addView(label, layoutTop(6));
                LinearLayout row = row();
                row.addView(chip("High Quality (85%)", toolCompressLevel == 85, () -> { toolCompressLevel = 85; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s1 = new View(this); row.addView(s1, lp(6, 1));
                row.addView(chip("Balanced (70%)", toolCompressLevel == 70, () -> { toolCompressLevel = 70; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s2 = new View(this); row.addView(s2, lp(6, 1));
                row.addView(chip("Max Compress (40%)", toolCompressLevel == 40, () -> { toolCompressLevel = 40; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(row, layoutTop(8));
                break;
            }
            case "annotate": {
                TextView label = text("Watermark or footer text:", 13, false, muted);
                container.addView(label, layoutTop(6));
                EditText wt = new EditText(this);
                wt.setHint("e.g. CONFIDENTIAL · PAGE {P}");
                wt.setText(toolWatermarkText);
                styleEditText(wt);
                wt.setPadding(dp(12), dp(10), dp(12), dp(10));
                wt.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        toolWatermarkText = s.toString();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(wt, layoutTop(8));
                break;
            }
            case "stamp": {
                TextView label = text("Choose stamp preset:", 13, false, muted);
                container.addView(label, layoutTop(6));
                String[] presets = {"CONFIDENTIAL", "APPROVED", "DRAFT", "FINAL", "COPY", "VOID"};
                LinearLayout r1 = row();
                for (int i = 0; i < 3; i++) {
                    final String p = presets[i];
                    if (i > 0) { View s = new View(this); r1.addView(s, lp(6, 1)); }
                    r1.addView(chip(p, p.equals(toolStampText), () -> { toolStampText = p; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                }
                container.addView(r1, layoutTop(8));
                LinearLayout r2 = row();
                for (int i = 3; i < 6; i++) {
                    final String p = presets[i];
                    if (i > 3) { View s = new View(this); r2.addView(s, lp(6, 1)); }
                    r2.addView(chip(p, p.equals(toolStampText), () -> { toolStampText = p; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                }
                container.addView(r2, layoutTop(6));

                TextView customLabel = text("Custom stamp text:", 13, false, muted);
                container.addView(customLabel, layoutTop(12));
                EditText st = new EditText(this);
                st.setText(toolStampText);
                styleEditText(st);
                st.setPadding(dp(12), dp(10), dp(12), dp(10));
                st.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        toolStampText = s.toString();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(st, layoutTop(6));
                break;
            }
            case "pdfa":
            case "repairPdf":
            case "doc_repair": {
                TextView info = text("This operation processes all internal streams and rebuilds corrupted structures offline.", 13, false, muted);
                container.addView(info, layoutTop(6));
                break;
            }
            case "fillForm": {
                TextView label = text("Form field values (field=value per line):", 13, false, muted);
                container.addView(label, layoutTop(6));
                EditText formInput = new EditText(this);
                formInput.setHint("Name=John Doe\nDate=2026-10-06");
                formInput.setText(toolFormValues);
                formInput.setMinLines(3);
                styleEditText(formInput);
                formInput.setPadding(dp(12), dp(10), dp(12), dp(10));
                formInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        toolFormValues = s.toString();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(formInput, layoutTop(8));

                CheckBox flatten = new CheckBox(this);
                flatten.setText("Flatten form fields (make non-editable)");
                flatten.setTextColor(ink);
                flatten.setChecked(toolFlattenForm);
                flatten.setOnCheckedChangeListener((v, c) -> toolFlattenForm = c);
                container.addView(flatten, layoutTop(8));
                break;
            }
            case "sign": {
                TextView label = text("Signer Name:", 13, false, muted);
                container.addView(label, layoutTop(6));
                EditText signInput = new EditText(this);
                signInput.setHint("e.g. John Doe");
                signInput.setText(toolSignerName);
                styleEditText(signInput);
                signInput.setPadding(dp(12), dp(10), dp(12), dp(10));
                signInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) {
                        toolSignerName = s.toString();
                    }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(signInput, layoutTop(8));
                break;
            }
            case "editPdfMeta": {
                EditText tInput = new EditText(this); tInput.setHint("Title"); tInput.setText(toolMetaTitle); styleEditText(tInput); tInput.setPadding(dp(12), dp(8), dp(12), dp(8));
                tInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolMetaTitle = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(tInput, layoutTop(6));

                EditText aInput = new EditText(this); aInput.setHint("Author"); aInput.setText(toolMetaAuthor); styleEditText(aInput); aInput.setPadding(dp(12), dp(8), dp(12), dp(8));
                aInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolMetaAuthor = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(aInput, layoutTop(6));

                EditText sInput = new EditText(this); sInput.setHint("Subject"); sInput.setText(toolMetaSubject); styleEditText(sInput); sInput.setPadding(dp(12), dp(8), dp(12), dp(8));
                sInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolMetaSubject = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(sInput, layoutTop(6));

                EditText kInput = new EditText(this); kInput.setHint("Keywords"); kInput.setText(toolMetaKeywords); styleEditText(kInput); kInput.setPadding(dp(12), dp(8), dp(12), dp(8));
                kInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolMetaKeywords = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(kInput, layoutTop(6));
                break;
            }
            case "crop": {
                TextView label = text("Margin to trim in points (default: 12):", 13, false, muted);
                container.addView(label, layoutTop(6));
                EditText cInput = new EditText(this);
                cInput.setText(toolCropMargin);
                cInput.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
                styleEditText(cInput);
                cInput.setPadding(dp(12), dp(10), dp(12), dp(10));
                cInput.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolCropMargin = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(cInput, layoutTop(8));

                LinearLayout row = row();
                row.addView(chip("Tight (6 pt)", "6".equals(toolCropMargin), () -> { toolCropMargin = "6"; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s1 = new View(this); row.addView(s1, lp(6, 1));
                row.addView(chip("Standard (12 pt)", "12".equals(toolCropMargin), () -> { toolCropMargin = "12"; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s2 = new View(this); row.addView(s2, lp(6, 1));
                row.addView(chip("Wide (24 pt)", "24".equals(toolCropMargin), () -> { toolCropMargin = "24"; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(row, layoutTop(8));
                break;
            }
            case "protect": {
                TextView label = text("Set password protection (min 4 characters):", 13, false, muted);
                container.addView(label, layoutTop(6));
                EditText pw = new EditText(this);
                pw.setHint("Enter password");
                pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                pw.setText(toolPassword);
                styleEditText(pw);
                pw.setPadding(dp(12), dp(10), dp(12), dp(10));
                pw.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolPassword = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(pw, layoutTop(8));

                CheckBox cp = new CheckBox(this); cp.setText("Allow Printing"); cp.setTextColor(ink); cp.setChecked(toolAllowPrint);
                cp.setOnCheckedChangeListener((v, c) -> toolAllowPrint = c);
                container.addView(cp, layoutTop(8));

                CheckBox cc = new CheckBox(this); cc.setText("Allow Text & Content Copying"); cc.setTextColor(ink); cc.setChecked(toolAllowCopy);
                cc.setOnCheckedChangeListener((v, c) -> toolAllowCopy = c);
                container.addView(cc, layoutTop(4));
                break;
            }
            case "unlock": {
                TextView label = text("Enter existing document password:", 13, false, muted);
                container.addView(label, layoutTop(6));
                EditText pw = new EditText(this);
                pw.setHint("Enter password");
                pw.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
                pw.setText(toolPassword);
                styleEditText(pw);
                pw.setPadding(dp(12), dp(10), dp(12), dp(10));
                pw.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolPassword = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(pw, layoutTop(8));
                break;
            }
            case "metadata": {
                TextView label = text("Document Information & Structure:", 13, false, muted);
                container.addView(label, layoutTop(6));
                TextView infoView = text(toolPdfPageCount > 0 ? (toolPdfPageCount + " total pages") : "Loading details...", 14, true, ink);
                container.addView(infoView, layoutTop(4));
                break;
            }
            case "image_convert": {
                TextView label = text("Target Image Format:", 13, false, muted);
                container.addView(label, layoutTop(6));
                String[] formats = {"jpg", "png", "webp", "bmp"};
                LinearLayout row = row();
                for (int i = 0; i < formats.length; i++) {
                    final String fmt = formats[i];
                    if (i > 0) { View s = new View(this); row.addView(s, lp(6, 1)); }
                    row.addView(chip(fmt.toUpperCase(Locale.ROOT), fmt.equalsIgnoreCase(toolImageTarget), () -> {
                        toolImageTarget = fmt;
                        renderToolPage();
                    }), new LinearLayout.LayoutParams(0, -2, 1));
                }
                container.addView(row, layoutTop(8));

                TextView qLabel = text("Quality: " + toolImageQuality + "%", 13, false, muted);
                container.addView(qLabel, layoutTop(12));
                LinearLayout qRow = row();
                int[] quals = {100, 90, 80, 60};
                for (int i = 0; i < quals.length; i++) {
                    final int q = quals[i];
                    if (i > 0) { View s = new View(this); qRow.addView(s, lp(6, 1)); }
                    qRow.addView(chip(q + "%", toolImageQuality == q, () -> { toolImageQuality = q; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                }
                container.addView(qRow, layoutTop(6));
                break;
            }
            case "image_exif": {
                EditText dIn = new EditText(this); dIn.setHint("Description"); dIn.setText(toolExifDesc); styleEditText(dIn); dIn.setPadding(dp(12), dp(8), dp(12), dp(8));
                dIn.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolExifDesc = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(dIn, layoutTop(6));

                EditText aIn = new EditText(this); aIn.setHint("Artist / Author"); aIn.setText(toolExifArtist); styleEditText(aIn); aIn.setPadding(dp(12), dp(8), dp(12), dp(8));
                aIn.addTextChangedListener(new android.text.TextWatcher() {
                    public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                    public void onTextChanged(CharSequence s, int start, int before, int count) { toolExifArtist = s.toString(); }
                    public void afterTextChanged(android.text.Editable s) {}
                });
                container.addView(aIn, layoutTop(6));

                CheckBox scrub = new CheckBox(this);
                scrub.setText("Scrub GPS Location Data");
                scrub.setTextColor(ink);
                scrub.setChecked(toolExifScrubGps);
                scrub.setOnCheckedChangeListener((v, c) -> toolExifScrubGps = c);
                container.addView(scrub, layoutTop(8));
                break;
            }
            case "image_contact_sheet": {
                TextView label = text("Grid Columns:", 13, false, muted);
                container.addView(label, layoutTop(6));
                LinearLayout row = row();
                row.addView(chip("2 Columns", toolContactSheetCols == 2, () -> { toolContactSheetCols = 2; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s1 = new View(this); row.addView(s1, lp(6, 1));
                row.addView(chip("3 Columns", toolContactSheetCols == 3, () -> { toolContactSheetCols = 3; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                View s2 = new View(this); row.addView(s2, lp(6, 1));
                row.addView(chip("4 Columns", toolContactSheetCols == 4, () -> { toolContactSheetCols = 4; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(row, layoutTop(8));
                break;
            }
            case "doc_convert": {
                TextView label = text("Target Document Format:", 13, false, muted);
                container.addView(label, layoutTop(6));
                String[] formats = {"pdf", "docx", "txt", "html", "csv"};
                LinearLayout row = row();
                for (int i = 0; i < formats.length; i++) {
                    final String fmt = formats[i];
                    if (i > 0) { View s = new View(this); row.addView(s, lp(6, 1)); }
                    row.addView(chip(fmt.toUpperCase(Locale.ROOT), fmt.equalsIgnoreCase(toolDocTarget), () -> {
                        toolDocTarget = fmt;
                        renderToolPage();
                    }), new LinearLayout.LayoutParams(0, -2, 1));
                }
                container.addView(row, layoutTop(8));
                break;
            }
            case "pdf_create": {
                TextView info = text("Images will be combined in the order listed above into a single PDF document.", 13, false, muted);
                container.addView(info, layoutTop(6));
                break;
            }
            case "pdf_searchable_ocr":
            case "ocr_extract_text": {
                TextView label = text("OCR Recognition Language:", 13, false, muted);
                container.addView(label, layoutTop(6));
                LinearLayout row = row();
                row.addView(chip("Latin / English", "latin".equals(toolOcrLang), () -> { toolOcrLang = "latin"; renderToolPage(); }), new LinearLayout.LayoutParams(0, -2, 1));
                container.addView(row, layoutTop(8));
                break;
            }
            case "zip_create": {
                TextView info = text("Compresses all selected files into a single ZIP archive.", 13, false, muted);
                container.addView(info, layoutTop(6));
                break;
            }
            case "zip_extract": {
                TextView info = text("Extracts all archive files directly into local device storage.", 13, false, muted);
                container.addView(info, layoutTop(6));
                break;
            }
        }
    }

    private void executeActiveTool() {
        if (toolProcessing) return;
        if (toolSources.isEmpty()) {
            toast("Please choose a file first");
            return;
        }
        if ("merge".equals(activeToolId) && toolSources.size() < 2) {
            toast("Select at least two PDF files to merge");
            return;
        }
        if ("addImage".equals(activeToolId) && toolSecondaryUri == null) {
            toast("Please choose an image to insert");
            return;
        }
        if ("protect".equals(activeToolId) && (toolPassword == null || toolPassword.length() < 4)) {
            toast("Password must be at least 4 characters");
            return;
        }
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            toolProcessing = false;
            toolStatusText = "Error: Insufficient storage space on device";
            renderToolPage();
            return;
        }

        cancelBatch = false;
        toolProcessing = true;
        toolStatusText = "Processing " + getToolTitle(activeToolId).toLowerCase(Locale.ROOT) + "...";
        toolResultFiles.clear();
        notifManager.showProgress(1, 1, getToolTitle(activeToolId));
        renderToolPage();

        worker.execute(() -> {
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) {
                runOnUiThread(() -> {
                    toolProcessing = false;
                    toolStatusText = "Error: Storage is unavailable";
                    toast("Storage is unavailable");
                    renderToolPage();
                });
                return;
            }
            final ArrayList<OutputFile> results = new ArrayList<>();
            String errorMsg = null;

            try {
                switch (activeToolId) {
                    case "merge": {
                        File out = PdfOperations.merge(getContentResolver(), toolSources, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "split": {
                        ArrayList<File> outs = PdfOperations.split(getContentResolver(), toolSources.get(0), dir);
                        boolean hasCustom = toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty();
                        String baseCustom = hasCustom ? safeName(toolCustomOutputName.trim().replaceFirst("\\.[^.]*$", "")) : "";
                        for (int i = 0; i < outs.size(); i++) {
                            File f = outs.get(i);
                            String fName = hasCustom ? (baseCustom + "-page-" + (i + 1) + ".pdf") : stripUuidPrefix(f.getName());
                            results.add(new OutputFile(f, fName, "application/pdf", "pdf"));
                        }
                        break;
                    }
                    case "extract": {
                        int count = PdfOperations.pageCount(getContentResolver(), toolSources.get(0));
                        String range = toolPageRange.trim().isEmpty() ? "1" : toolPageRange.trim();
                        ArrayList<Integer> pages = parsePages(range, count);
                        File out = PdfOperations.extract(getContentResolver(), toolSources.get(0), pages, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "delete": {
                        int count = PdfOperations.pageCount(getContentResolver(), toolSources.get(0));
                        String range = toolPageRange.trim().isEmpty() ? "1" : toolPageRange.trim();
                        ArrayList<Integer> pages = parsePages(range, count);
                        File out = PdfOperations.deletePages(getContentResolver(), toolSources.get(0), pages, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "reorder": {
                        int count = PdfOperations.pageCount(getContentResolver(), toolSources.get(0));
                        ArrayList<Integer> pages;
                        if (!toolReorderedPages.isEmpty()) {
                            pages = new ArrayList<>(toolReorderedPages);
                        } else if (!toolPageRange.trim().isEmpty()) {
                            pages = parsePages(toolPageRange.trim(), count);
                        } else {
                            pages = new ArrayList<>();
                            for (int i = 0; i < count; i++) pages.add(i);
                        }
                        File out = PdfOperations.reorder(getContentResolver(), toolSources.get(0), pages, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "rotate": {
                        File out = PdfOperations.rotate(getContentResolver(), toolSources.get(0), toolRotateAngle, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "resize": {
                        File out;
                        if (toolResizePreset >= 0) {
                            out = PdfOperations.resizePages(getContentResolver(), toolSources.get(0), toolResizePreset, 150, dir);
                        } else {
                            out = PdfOperations.resizePagesByPercent(getContentResolver(), toolSources.get(0), toolResizePercent, 150, dir);
                        }
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "addImage": {
                        if (toolSecondaryUri == null) throw new IOException("Please select an image to insert");
                        String imgType = detectType(toolSecondaryUri, displayName(toolSecondaryUri));
                        Bitmap bmp = ImageTools.decode(getContentResolver(), toolSecondaryUri, imgType);
                        try {
                            File out = PdfOperations.insertImage(getContentResolver(), toolSources.get(0), bmp, toolSignPage + 1, dir);
                            String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                    ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                    : stripUuidPrefix(out.getName());
                            results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        } finally {
                            if (bmp != null) bmp.recycle();
                        }
                        break;
                    }
                    case "compress": {
                        File out = PdfOperations.compress(getContentResolver(), toolSources.get(0), toolCompressLevel, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "annotate": {
                        if (toolWatermarkText.trim().isEmpty()) throw new IOException("Enter watermark or footer text");
                        File out = PdfOperations.annotate(getContentResolver(), toolSources.get(0), toolWatermarkText.trim(), dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "stamp": {
                        File out = PdfOperations.stamp(getContentResolver(), toolSources.get(0), toolStampText, toolStampPos, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "pdfa": {
                        File out = PdfOperations.convertToPdfA(getContentResolver(), toolSources.get(0), dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "repairPdf": {
                        File out = PdfOperations.repair(getContentResolver(), toolSources.get(0), dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "fillForm": {
                        File out = PdfOperations.fillForm(getContentResolver(), toolSources.get(0), toolFormValues, toolFlattenForm, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "sign": {
                        String name = toolSignerName.trim().isEmpty() ? "Verified Signature" : toolSignerName.trim();
                        File out = PdfOperations.addSignature(getContentResolver(), toolSources.get(0), name, toolSignPage, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "editPdfMeta": {
                        File out = PdfOperations.editMetadata(getContentResolver(), toolSources.get(0), toolMetaTitle, toolMetaAuthor, toolMetaSubject, toolMetaKeywords, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "crop": {
                        float margin = Float.parseFloat(toolCropMargin.trim());
                        File out = PdfOperations.crop(getContentResolver(), toolSources.get(0), margin, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "protect": {
                        int perms = (toolAllowPrint ? 1 : 0) | (toolAllowCopy ? 2 : 0) | (toolAllowEdit ? 4 : 0);
                        File out = PdfOperations.protect(getContentResolver(), toolSources.get(0), toolPassword, perms, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "unlock": {
                        File out = PdfOperations.removePassword(getContentResolver(), toolSources.get(0), toolPassword, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "metadata": {
                        String meta = PdfOperations.metadata(getContentResolver(), toolSources.get(0));
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "txt")
                                : "PDF-Details.txt";
                        File metaFile = new File(dir, UUID.randomUUID() + "-" + safeName(finalName));
                        try (FileOutputStream fos = new FileOutputStream(metaFile)) {
                            fos.write(meta.getBytes(StandardCharsets.UTF_8));
                        }
                        results.add(new OutputFile(metaFile, finalName, "text/plain", "txt"));
                        break;
                    }
                    case "image_convert": {
                        boolean hasCustom = toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty();
                        String baseCustom = hasCustom ? safeName(toolCustomOutputName.trim().replaceFirst("\\.[^.]*$", "")) : "";
                        for (int i = 0; i < toolSources.size(); i++) {
                            Uri u = toolSources.get(i);
                            String name = displayName(u);
                            String srcType = detectType(u, name);
                            Bitmap original = ImageTools.decode(getContentResolver(), u, srcType);
                            String ext = toolImageTarget;
                            String outName;
                            if (hasCustom) {
                                outName = (toolSources.size() == 1)
                                        ? ensureExtension(toolCustomOutputName.trim(), ext)
                                        : ensureExtension(baseCustom + "-" + (i + 1), ext);
                            } else {
                                outName = ensureExtension(safeName(name).replaceAll("\\.[^.]+$", ""), ext);
                            }
                            File out = new File(dir, UUID.randomUUID() + "-" + safeName(outName));
                            try (FileOutputStream fos = new FileOutputStream(out)) {
                                Bitmap.CompressFormat fmt = Bitmap.CompressFormat.JPEG;
                                if ("png".equalsIgnoreCase(toolImageTarget)) fmt = Bitmap.CompressFormat.PNG;
                                else if ("webp".equalsIgnoreCase(toolImageTarget)) {
                                    if (Build.VERSION.SDK_INT >= 30) fmt = Bitmap.CompressFormat.WEBP_LOSSY;
                                    else fmt = Bitmap.CompressFormat.WEBP;
                                }
                                original.compress(fmt, toolImageQuality, fos);
                            }
                            ImageTools.setDpi(out, toolImageDpi);
                            if (Arrays.asList("jpg", "jpeg").contains(toolImageTarget.toLowerCase(Locale.ROOT))) {
                                ImageTools.copyExif(getContentResolver(), u, out);
                            }
                            original.recycle();
                            String mime = resolveMimeType(out.getName(), "image/" + toolImageTarget, toolImageTarget);
                            results.add(new OutputFile(out, outName, mime, toolImageTarget));
                        }
                        break;
                    }
                    case "image_exif": {
                        Uri u = toolSources.get(0);
                        String origName = displayName(u);
                        String ext = origName.contains(".") ? origName.substring(origName.lastIndexOf('.') + 1) : "jpg";
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), ext)
                                : safeName(origName);
                        File dest = new File(dir, UUID.randomUUID() + "-" + safeName(finalName));
                        try (InputStream in = getContentResolver().openInputStream(u); FileOutputStream out = new FileOutputStream(dest)) {
                            byte[] buf = new byte[32768];
                            int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        ImageTools.editExif(dest, toolExifDesc.trim(), toolExifArtist.trim(), toolExifCopyright.trim(), toolExifMake.trim(), toolExifModel.trim(), toolExifScrubGps);
                        results.add(new OutputFile(dest, finalName, "image/jpeg", "jpg"));
                        break;
                    }
                    case "image_contact_sheet": {
                        int cols = toolContactSheetCols;
                        int rows = (int) Math.ceil((double) toolSources.size() / cols);
                        File sheet = ImageTools.generateContactSheet(getContentResolver(), toolSources, cols, rows, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "png")
                                : stripUuidPrefix(sheet.getName());
                        results.add(new OutputFile(sheet, finalName, "image/png", "png"));
                        break;
                    }
                    case "doc_convert": {
                        boolean hasCustom = toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty();
                        String baseCustom = hasCustom ? safeName(toolCustomOutputName.trim().replaceFirst("\\.[^.]*$", "")) : "";
                        for (int i = 0; i < toolSources.size(); i++) {
                            Uri u = toolSources.get(i);
                            String name = displayName(u);
                            String srcType = detectType(u, name);
                            String nameForConversion = hasCustom
                                    ? ((toolSources.size() == 1) ? toolCustomOutputName.trim() : (baseCustom + "-" + (i + 1)))
                                    : name;
                            OfficeTools.Result r = OfficeTools.convert(getContentResolver(), u, nameForConversion, srcType, toolDocTarget, dir);
                            if (r != null && r.file != null) {
                                String outName = hasCustom
                                        ? ((toolSources.size() == 1) ? ensureExtension(toolCustomOutputName.trim(), toolDocTarget) : ensureExtension(baseCustom + "-" + (i + 1), toolDocTarget))
                                        : stripUuidPrefix(r.file.getName());
                                String mime = resolveMimeType(r.file.getName(), null, toolDocTarget);
                                results.add(new OutputFile(r.file, outName, mime, toolDocTarget));
                            }
                        }
                        break;
                    }
                    case "doc_repair": {
                        Uri u = toolSources.get(0);
                        String name = displayName(u);
                        String type = detectType(u, name);
                        String nameForConversion = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? toolCustomOutputName.trim()
                                : name;
                        OfficeTools.Result res = OfficeTools.repairDocument(getContentResolver(), u, nameForConversion, type, dir);
                        if (res != null && res.file != null) {
                            String outName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                    ? ensureExtension(toolCustomOutputName.trim(), "docx")
                                    : stripUuidPrefix(res.file.getName());
                            results.add(new OutputFile(res.file, outName, "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"));
                        } else {
                            throw new IOException("Unable to repair document structure");
                        }
                        break;
                    }
                    case "pdf_create": {
                        ArrayList<Bitmap> bmps = new ArrayList<>();
                        try {
                            for (Uri u : toolSources) {
                                String srcType = detectType(u, displayName(u));
                                Bitmap b = ImageTools.decode(getContentResolver(), u, srcType);
                                if (b != null) bmps.add(b);
                            }
                            if (bmps.isEmpty()) throw new IOException("Could not decode selected images");
                            String custom = (toolCustomOutputName == null || toolCustomOutputName.trim().isEmpty()) ? "FileSwitch-images.pdf" : ensureExtension(toolCustomOutputName.trim(), "pdf");
                            File out = new File(dir, UUID.randomUUID() + "-" + safeName(custom));
                            writePdf(bmps, out);
                            if (toolPassword != null && toolPassword.length() >= 4) {
                                PdfOperations.protectInPlace(out, toolPassword);
                            }
                            results.add(new OutputFile(out, custom, "application/pdf", "pdf"));
                        } finally {
                            for (Bitmap b : bmps) b.recycle();
                        }
                        break;
                    }
                    case "pdf_searchable_ocr": {
                        Uri u = toolSources.get(0);
                        String type = detectType(u, displayName(u));
                        File out = OcrTools.searchablePdf(getContentResolver(), u, type, toolOcrLang, dir);
                        String finalName = (toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty())
                                ? ensureExtension(toolCustomOutputName.trim(), "pdf")
                                : stripUuidPrefix(out.getName());
                        results.add(new OutputFile(out, finalName, "application/pdf", "pdf"));
                        break;
                    }
                    case "ocr_extract_text": {
                        boolean hasCustom = toolCustomOutputName != null && !toolCustomOutputName.trim().isEmpty();
                        String baseCustom = hasCustom ? safeName(toolCustomOutputName.trim().replaceFirst("\\.[^.]*$", "")) : "";
                        for (int i = 0; i < toolSources.size(); i++) {
                            Uri u = toolSources.get(i);
                            String type = detectType(u, displayName(u));
                            String recognized = type.equals("pdf") ? OcrTools.pdfText(getContentResolver(), u, toolOcrLang) : OcrTools.imageText(getContentResolver(), u, type, toolOcrLang);
                            String baseName = safeName(displayName(u)).replaceAll("\\.[^.]+$", "");
                            String outName = hasCustom
                                    ? ((toolSources.size() == 1) ? ensureExtension(toolCustomOutputName.trim(), "txt") : ensureExtension(baseCustom + "-" + (i + 1), "txt"))
                                    : (baseName + "-ocr.txt");
                            File txtFile = new File(dir, UUID.randomUUID() + "-" + safeName(outName));
                            try (FileOutputStream fos = new FileOutputStream(txtFile)) {
                                fos.write(recognized.getBytes(StandardCharsets.UTF_8));
                            }
                            results.add(new OutputFile(txtFile, outName, "text/plain", "txt"));
                        }
                        break;
                    }
                    case "zip_create": {
                        String custom = (toolCustomOutputName == null || toolCustomOutputName.trim().isEmpty()) ? "FileSwitch-files.zip" : ensureExtension(toolCustomOutputName.trim(), "zip");
                        File zip = new File(dir, UUID.randomUUID() + "-" + safeName(custom));
                        HashSet<String> names = new HashSet<>();
                        long total = 0;
                        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                            for (Uri u : toolSources) {
                                String base = safeName(displayName(u));
                                if (base.isEmpty()) base = "file";
                                String entryName = base;
                                int suffix = 2;
                                while (!names.add(entryName)) {
                                    int dot = base.lastIndexOf('.');
                                    entryName = dot > 0 ? base.substring(0, dot) + " (" + suffix++ + ")" + base.substring(dot) : base + " (" + suffix++ + ")";
                                }
                                zos.putNextEntry(new ZipEntry(entryName));
                                try (InputStream in = getContentResolver().openInputStream(u)) {
                                    if (in != null) {
                                        byte[] buf = new byte[32768];
                                        int n;
                                        while ((n = in.read(buf)) > 0) {
                                            total += n;
                                            if (total > 500L * 1024 * 1024) throw new IOException("Files exceed the 500 MB ZIP limit");
                                            zos.write(buf, 0, n);
                                        }
                                    }
                                }
                                zos.closeEntry();
                            }
                        }
                        results.add(new OutputFile(zip, custom, "application/zip", "zip"));
                        break;
                    }
                    case "zip_extract": {
                        ArrayList<ZipTools.Entry> extracted = ZipTools.extract(getContentResolver(), toolSources.get(0), dir);
                        for (ZipTools.Entry e : extracted) {
                            results.add(new OutputFile(e.file, e.name, e.mime, "extracted"));
                        }
                        break;
                    }
                    default:
                        throw new IOException("Tool operation not implemented");
                }
            } catch (Exception e) {
                errorMsg = getHumanReadableErrorMessage(e, "Operation failed");
            }

            if (errorMsg == null) {
                if (results.isEmpty()) {
                    errorMsg = "No output file was created";
                } else {
                    for (OutputFile out : results) {
                        if (out == null || out.file == null || !out.file.exists() || out.file.length() <= 0) {
                            errorMsg = "Output file could not be created or is empty";
                            break;
                        }
                    }
                }
            }

            if (errorMsg == null && !results.isEmpty()) {
                ArrayList<OutputFile> savedResults = new ArrayList<>();
                for (OutputFile out : results) {
                    OutputFile saved = saveOutputFileToFileSwitchDir(out);
                    if (saved == null || saved.file == null || !saved.file.exists() || saved.file.length() <= 0) {
                        errorMsg = "Could not save output file to FileSwitch folder";
                        break;
                    }
                    savedResults.add(saved);
                }
                if (errorMsg == null) {
                    results.clear();
                    results.addAll(savedResults);
                }
            }

            final String err = errorMsg;
            if (cancelBatch) {
                notifManager.showFailed("Operation cancelled");
            } else if (err != null || results.isEmpty()) {
                notifManager.showFailed(err != null ? err : "Operation failed");
            } else {
                notifManager.showComplete(results.size());
            }

            runOnUiThread(() -> {
                toolProcessing = false;
                if (cancelBatch) {
                    toolResultFiles.clear();
                    toolStatusText = "Operation cancelled";
                    toast("Operation cancelled");
                    if (!toolSources.isEmpty()) {
                        saveHistory(displayName(toolSources.get(0)), getToolTitle(activeToolId), "file", 0, "cancelled", null, null);
                    }
                } else if (err != null || results.isEmpty()) {
                    toolResultFiles.clear();
                    toolStatusText = "Error: " + (err != null ? err : "Operation failed");
                    toast(err != null ? err : "Operation failed");
                    if (!toolSources.isEmpty()) {
                        saveHistory(displayName(toolSources.get(0)), getToolTitle(activeToolId), "file", 0, "failed", null, null);
                    }
                } else {
                    toolResultFiles.clear();
                    toolResultFiles.addAll(results);
                    toolStatusText = "Saved to FileSwitch folder (" + results.size() + (results.size() == 1 ? " file)" : " files)");
                    if (!toolSources.isEmpty()) {
                        for (OutputFile out : results) {
                            saveHistory(out.name, getToolTitle(activeToolId), out.target, out.file.length(), "complete", out.file.getAbsolutePath(), out.mime);
                        }
                    }
                    toast("✓ " + getToolTitle(activeToolId) + " saved to FileSwitch");
                }
                renderToolPage();
            });
        });
    }

    private void openPdfOperation(String operation) {
        openToolPage(operation);
    }

    private interface ThumbnailCallback {
        void onLoaded(Bitmap bitmap);
    }

    private void loadPdfPageThumbnail(Uri uri, int pageIndex, int maxDim, ThumbnailCallback callback) {
        worker.execute(() -> {
            Bitmap bmp = null;
            if (Build.VERSION.SDK_INT >= 21) {
                try (android.os.ParcelFileDescriptor pfd = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (pfd != null) {
                        android.graphics.pdf.PdfRenderer renderer = new android.graphics.pdf.PdfRenderer(pfd);
                        if (pageIndex >= 0 && pageIndex < renderer.getPageCount()) {
                            android.graphics.pdf.PdfRenderer.Page page = renderer.openPage(pageIndex);
                            int origW = page.getWidth();
                            int origH = page.getHeight();
                            float scale = Math.min((float) maxDim / Math.max(1, origW), (float) maxDim / Math.max(1, origH));
                            int w = Math.max(1, Math.round(origW * scale));
                            int h = Math.max(1, Math.round(origH * scale));
                            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            page.close();
                        }
                        renderer.close();
                    }
                } catch (Exception ignored) {}
            }
            if (bmp == null) {
                try (InputStream in = getContentResolver().openInputStream(uri); PDDocument doc = PDDocument.load(in)) {
                    if (pageIndex >= 0 && pageIndex < doc.getNumberOfPages()) {
                        PDFRenderer renderer = new PDFRenderer(doc);
                        bmp = renderer.renderImageWithDPI(pageIndex, 36, ImageType.RGB);
                    }
                } catch (Exception ignored) {}
            }
            final Bitmap finalBmp = bmp;
            runOnUiThread(() -> callback.onLoaded(finalBmp));
        });
    }

    private Dialog createVisualDialog(String title, String subtitle, View contentView, View bottomActions) {
        Dialog dialog = new Dialog(this, android.R.style.Theme_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout rootDlg = column();
        rootDlg.setBackgroundColor(surface);
        pad(rootDlg, 18, 16, 18, 16);

        LinearLayout header = row();
        LinearLayout titleCol = column();
        titleCol.addView(text(title, 18, true, ink));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, muted);
            LinearLayout.LayoutParams sp = lp(-2, -2);
            sp.topMargin = dp(2);
            titleCol.addView(sub, sp);
        }
        header.addView(titleCol, new LinearLayout.LayoutParams(0, -2, 1));

        TextView closeBtn = text("✕", 18, true, muted);
        closeBtn.setGravity(Gravity.CENTER);
        closeBtn.setContentDescription("Close dialog");
        closeBtn.setBackground(shape(bg, 14, 0));
        closeBtn.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn, lp(44, 44));
        rootDlg.addView(header, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, 0, 1);
        cp.topMargin = dp(12);
        cp.bottomMargin = dp(12);
        scroll.addView(contentView);
        rootDlg.addView(scroll, cp);

        if (bottomActions != null) {
            rootDlg.addView(bottomActions, lp(-1, -2));
        }

        dialog.setContentView(rootDlg, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(surface));
            dialog.getWindow().setFlags(
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN,
                android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN
            );
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            dialog.getWindow().setWindowAnimations(android.R.style.Animation_Dialog);
        }
        return dialog;
    }

    private void showResizePagesDialog(ArrayList<Uri> sources) {
        LinearLayout content = column();

        LinearLayout pctCard = column();
        pctCard.setBackground(shape(bg, 14, line));
        pad(pctCard, 14, 14, 14, 14);

        pctCard.addView(text("Scale by percentage", 15, true, ink));
        pctCard.addView(text("Resize page dimensions while preserving aspect ratio (1% to 100%).", 12, false, muted));

        final int[] selectedPercent = {75};
        TextView pctLabel = text("Resize: " + selectedPercent[0] + "%", 15, true, ink);
        LinearLayout.LayoutParams lpLabel = lp(-2, -2);
        lpLabel.topMargin = dp(10);
        pctCard.addView(pctLabel, lpLabel);

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMax(99);
        seekBar.setProgress(selectedPercent[0] - 1);
        LinearLayout.LayoutParams lpBar = lp(-1, 40);
        lpBar.topMargin = dp(4);
        pctCard.addView(seekBar, lpBar);

        TextView[] applyRef = new TextView[1];
        Dialog[] dialogRef = new Dialog[1];

        TextView applyPercentBtn = button("Resize to " + selectedPercent[0] + "%", true, () -> {
            if (dialogRef[0] != null) dialogRef[0].dismiss();
            processPdf("resize", sources, 0, String.valueOf(selectedPercent[0]));
        });
        applyRef[0] = applyPercentBtn;
        LinearLayout.LayoutParams lpBtn = lp(-1, 48);
        lpBtn.topMargin = dp(8);
        pctCard.addView(applyPercentBtn, lpBtn);

        content.addView(pctCard, lp(-1, -2));

        LinearLayout presetCard = column();
        presetCard.setBackground(shape(bg, 14, line));
        pad(presetCard, 14, 14, 14, 14);
        LinearLayout.LayoutParams lpPresetCard = lp(-1, -2);
        lpPresetCard.topMargin = dp(14);

        presetCard.addView(text("Or choose standard page size", 15, true, ink));
        presetCard.addView(text("Fit and center pages onto standard paper sheets.", 12, false, muted));

        String[] presets = {"A4 portrait", "A4 landscape", "Letter portrait", "Letter landscape"};
        LinearLayout presetList = column();
        LinearLayout.LayoutParams lpPresetList = lp(-1, -2);
        lpPresetList.topMargin = dp(8);

        for (int i = 0; i < presets.length; i++) {
            final int presetIndex = i;
            TextView btn = text(presets[i], 14, true, ink);
            btn.setGravity(Gravity.CENTER_VERTICAL);
            btn.setBackground(shape(surface, 10, line));
            pad(btn, 14, 10, 14, 10);
            btn.setOnClickListener(v -> {
                if (dialogRef[0] != null) dialogRef[0].dismiss();
                processPdf("resize", sources, presetIndex, null);
            });
            LinearLayout.LayoutParams pbp = lp(-1, 44);
            if (i > 0) pbp.topMargin = dp(6);
            presetList.addView(btn, pbp);
        }
        presetCard.addView(presetList, lpPresetList);
        content.addView(presetCard, lpPresetCard);

        dialogRef[0] = createVisualDialog("Resize PDF Pages", "Scale pages or fit to paper sizes", content, null);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                selectedPercent[0] = progress + 1;
                pctLabel.setText("Resize: " + selectedPercent[0] + "%");
                if (applyRef[0] != null) applyRef[0].setText("Resize to " + selectedPercent[0] + "%");
            }
        });

        dialogRef[0].show();
    }

    private void requestPdfOptions(String operation, ArrayList<Uri> sources) {
        if (operation.equals("resize")) {
            showResizePagesDialog(sources);
            return;
        }
        if (operation.equals("addImage")) {
            pendingPdfImageSources = sources;
            Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            picker.addCategory(Intent.CATEGORY_OPENABLE);
            picker.setType("image/*");
            try {
                startActivityForResult(picker, PICK_IMAGE_FOR_PDF);
            } catch (ActivityNotFoundException e) {
                toast("No image picker is available");
            }
            return;
        }
        if (operation.equals("extract") || operation.equals("delete")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualPageSelectionDialog(operation, sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("reorder")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualReorderDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("rotate")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualRotateDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("crop")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualCropDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("stamp")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualStampDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("annotate")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualAnnotateDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("fillForm")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualFillFormDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("sign")) {
            toast("Preparing PDF pages...");
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualSignDialog(sources, count));
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF page count");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        requestPdfOptionsLegacy(operation, sources);
    }

    private void showVisualPageSelectionDialog(String operation, ArrayList<Uri> sources, int totalPages) {
        boolean isDelete = "delete".equals(operation);
        Uri sourceUri = sources.get(0);
        HashSet<Integer> selectedPages = new HashSet<>();

        LinearLayout content = column();

        EditText rangeInput = new EditText(this);
        rangeInput.setHint("e.g. 1-3, 5, 8");
        rangeInput.setTextSize(14);
        rangeInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleEditText(rangeInput);
        content.addView(rangeInput, lp(-1, 46));

        LinearLayout chips = row();
        TextView allChip = button("All", false, null);
        TextView oddChip = button("Odd", false, null);
        TextView evenChip = button("Even", false, null);
        TextView clearChip = button("Clear", false, null);
        for (TextView chip : new TextView[]{allChip, oddChip, evenChip, clearChip}) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(36), 1);
            cp.leftMargin = dp(2);
            cp.rightMargin = dp(2);
            chips.addView(chip, cp);
        }
        LinearLayout.LayoutParams chp = lp(-1, 36);
        chp.topMargin = dp(8);
        content.addView(chips, chp);

        LinearLayout grid = column();
        LinearLayout.LayoutParams gp = lp(-1, -2);
        gp.topMargin = dp(12);
        content.addView(grid, gp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button(isDelete ? "Delete Pages" : "Extract Pages", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            isDelete ? "Delete Pages" : "Extract Pages",
            "Tap thumbnails or enter page numbers below",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        ArrayList<LinearLayout> cardViews = new ArrayList<>();
        ArrayList<ImageView> imageViews = new ArrayList<>();
        ArrayList<TextView> badgeViews = new ArrayList<>();

        Runnable updateUI = () -> {
            StringBuilder sb = new StringBuilder();
            ArrayList<Integer> sorted = new ArrayList<>(selectedPages);
            Collections.sort(sorted);
            for (int i = 0; i < sorted.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(sorted.get(i) + 1);
            }
            if (!rangeInput.hasFocus()) {
                rangeInput.setText(sb.toString());
            }

            for (int i = 0; i < totalPages; i++) {
                boolean isSelected = selectedPages.contains(i);
                LinearLayout card = cardViews.get(i);
                TextView badge = badgeViews.get(i);
                if (isSelected) {
                    card.setBackground(shape(isDark() ? Color.parseColor("#2A313C") : Color.parseColor("#EAF0FA"), 14, isDelete ? Color.parseColor("#E53935") : accent));
                    badge.setText(isDelete ? "✕ Delete" : "✓ Selected");
                    badge.setTextColor(isDelete ? Color.parseColor("#E53935") : accent);
                } else {
                    card.setBackground(shape(surface, 14, line));
                    badge.setText("Page " + (i + 1));
                    badge.setTextColor(muted);
                }
            }

            int count = selectedPages.size();
            if (isDelete) {
                confirmBtn.setText(count > 0 ? "Delete " + count + " " + (count == 1 ? "page" : "pages") : "Select pages to delete");
                confirmBtn.setEnabled(count > 0 && count < totalPages);
                confirmBtn.setAlpha(count > 0 && count < totalPages ? 1f : 0.5f);
            } else {
                confirmBtn.setText(count > 0 ? "Extract " + count + " " + (count == 1 ? "page" : "pages") : "Select pages to extract");
                confirmBtn.setEnabled(count > 0);
                confirmBtn.setAlpha(count > 0 ? 1f : 0.5f);
            }
        };

        allChip.setOnClickListener(v -> {
            selectedPages.clear();
            for (int i = 0; i < totalPages; i++) selectedPages.add(i);
            updateUI.run();
        });
        oddChip.setOnClickListener(v -> {
            selectedPages.clear();
            for (int i = 0; i < totalPages; i += 2) selectedPages.add(i);
            updateUI.run();
        });
        evenChip.setOnClickListener(v -> {
            selectedPages.clear();
            for (int i = 1; i < totalPages; i += 2) selectedPages.add(i);
            updateUI.run();
        });
        clearChip.setOnClickListener(v -> {
            selectedPages.clear();
            updateUI.run();
        });

        rangeInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (rangeInput.hasFocus()) {
                    try {
                        ArrayList<Integer> parsed = parsePages(s.toString().trim(), totalPages);
                        selectedPages.clear();
                        selectedPages.addAll(parsed);
                        for (int i = 0; i < totalPages; i++) {
                            boolean isSel = selectedPages.contains(i);
                            cardViews.get(i).setBackground(shape(isSel ? (isDark() ? Color.parseColor("#2A313C") : Color.parseColor("#EAF0FA")) : surface, 14, isSel ? (isDelete ? Color.parseColor("#E53935") : accent) : line));
                            badgeViews.get(i).setText(isSel ? (isDelete ? "✕ Delete" : "✓ Selected") : "Page " + (i + 1));
                            badgeViews.get(i).setTextColor(isSel ? (isDelete ? Color.parseColor("#E53935") : accent) : muted);
                        }
                        int c = selectedPages.size();
                        confirmBtn.setEnabled(isDelete ? (c > 0 && c < totalPages) : (c > 0));
                        confirmBtn.setAlpha(confirmBtn.isEnabled() ? 1f : 0.5f);
                    } catch (Exception ignored) {}
                }
            }
            public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout currentRow = null;
        for (int i = 0; i < totalPages; i++) {
            final int pageIdx = i;
            if (i % 2 == 0) {
                currentRow = row();
                LinearLayout.LayoutParams rp = lp(-1, -2);
                if (i > 0) rp.topMargin = dp(8);
                grid.addView(currentRow, rp);
            }
            LinearLayout card = column();
            pad(card, 8, 8, 8, 8);
            card.setBackground(shape(surface, 14, line));

            TextView badge = text("Page " + (i + 1), 11, true, muted);
            badge.setGravity(Gravity.CENTER);
            card.addView(badge, lp(-1, -2));

            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumbnail.setBackground(shape(bg, 8, 0));
            LinearLayout.LayoutParams tp = lp(-1, 120);
            tp.topMargin = dp(6);
            card.addView(thumbnail, tp);

            cardViews.add(card);
            badgeViews.add(badge);
            imageViews.add(thumbnail);

            card.setOnClickListener(v -> {
                if (selectedPages.contains(pageIdx)) {
                    selectedPages.remove(pageIdx);
                } else {
                    selectedPages.add(pageIdx);
                }
                updateUI.run();
            });

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 != 0) cp.leftMargin = dp(8);
            currentRow.addView(card, cp);

            loadPdfPageThumbnail(sourceUri, pageIdx, 200, bmp -> {
                if (bmp != null) thumbnail.setImageBitmap(bmp);
            });
        }

        if (totalPages % 2 != 0 && currentRow != null) {
            View empty = new View(this);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, 0, 1);
            ep.leftMargin = dp(8);
            currentRow.addView(empty, ep);
        }

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            StringBuilder sb = new StringBuilder();
            ArrayList<Integer> sorted = new ArrayList<>(selectedPages);
            Collections.sort(sorted);
            for (int p : sorted) {
                if (sb.length() > 0) sb.append(",");
                sb.append(p + 1);
            }
            processPdf(operation, sources, 0, sb.toString());
        });

        updateUI.run();
        dialog.show();
    }

    private void showVisualReorderDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        ArrayList<Integer> currentOrder = new ArrayList<>();
        for (int i = 0; i < totalPages; i++) currentOrder.add(i);

        LinearLayout content = column();

        TextView guide = text("Use arrows to rearrange pages, or tap two pages to swap them:", 13, false, muted);
        content.addView(guide);

        LinearLayout chips = row();
        TextView resetChip = button("Reset original order", false, null);
        TextView reverseChip = button("Reverse order", false, null);
        for (TextView chip : new TextView[]{resetChip, reverseChip}) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(36), 1);
            cp.leftMargin = dp(3);
            cp.rightMargin = dp(3);
            chips.addView(chip, cp);
        }
        LinearLayout.LayoutParams chp = lp(-1, 36);
        chp.topMargin = dp(8);
        content.addView(chips, chp);

        LinearLayout list = column();
        LinearLayout.LayoutParams lp = lp(-1, -2);
        lp.topMargin = dp(12);
        content.addView(list, lp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Apply Reorder", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Reorder Pages",
            "Rearrange PDF page sequence",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        HashMap<Integer, Bitmap> thumbCache = new HashMap<>();
        for (int i = 0; i < totalPages; i++) {
            final int p = i;
            loadPdfPageThumbnail(sourceUri, p, 120, bmp -> {
                if (bmp != null) thumbCache.put(p, bmp);
            });
        }

        final int[] swapSource = {-1};

        Runnable renderList = new Runnable() {
            @Override
            public void run() {
                list.removeAllViews();
                for (int pos = 0; pos < currentOrder.size(); pos++) {
                    final int position = pos;
                    final int originalPage = currentOrder.get(pos);

                    LinearLayout item = row();
                    pad(item, 10, 8, 10, 8);
                    boolean isSwapTarget = swapSource[0] == position;
                    item.setBackground(shape(isSwapTarget ? (isDark() ? Color.parseColor("#2A313C") : Color.parseColor("#EAF0FA")) : surface, 14, isSwapTarget ? accent : line));

                    TextView posBadge = text("#" + (position + 1), 13, true, accent);
                    item.addView(posBadge, lp(32, -2));

                    ImageView thumb = new ImageView(MainActivity.this);
                    thumb.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    thumb.setBackground(shape(bg, 8, 0));
                    if (thumbCache.containsKey(originalPage)) {
                        thumb.setImageBitmap(thumbCache.get(originalPage));
                    } else {
                        loadPdfPageThumbnail(sourceUri, originalPage, 120, bmp -> {
                            if (bmp != null) {
                                thumbCache.put(originalPage, bmp);
                                thumb.setImageBitmap(bmp);
                            }
                        });
                    }
                    LinearLayout.LayoutParams tp = lp(48, 54);
                    tp.leftMargin = dp(8);
                    item.addView(thumb, tp);

                    LinearLayout info = column();
                    info.addView(text("Page " + (originalPage + 1), 14, true, ink));
                    TextView posInfo = text("Position " + (position + 1) + " of " + totalPages, 11, false, muted);
                    info.addView(posInfo);
                    LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
                    ip.leftMargin = dp(10);
                    item.addView(info, ip);

                    if (position > 0) {
                        TextView upBtn = text("▲", 16, true, ink);
                        upBtn.setGravity(Gravity.CENTER);
                        upBtn.setBackground(shape(bg, 10, 0));
                        upBtn.setOnClickListener(v -> {
                            Collections.swap(currentOrder, position, position - 1);
                            swapSource[0] = -1;
                            run();
                        });
                        item.addView(upBtn, lp(34, 34));
                    }
                    if (position < currentOrder.size() - 1) {
                        TextView downBtn = text("▼", 16, true, ink);
                        downBtn.setGravity(Gravity.CENTER);
                        downBtn.setBackground(shape(bg, 10, 0));
                        downBtn.setOnClickListener(v -> {
                            Collections.swap(currentOrder, position, position + 1);
                            swapSource[0] = -1;
                            run();
                        });
                        LinearLayout.LayoutParams dp = lp(34, 34);
                        dp.leftMargin = dp(6);
                        item.addView(downBtn, dp);
                    }

                    item.setOnClickListener(v -> {
                        if (swapSource[0] < 0) {
                            swapSource[0] = position;
                            run();
                        } else if (swapSource[0] == position) {
                            swapSource[0] = -1;
                            run();
                        } else {
                            Collections.swap(currentOrder, swapSource[0], position);
                            swapSource[0] = -1;
                            run();
                        }
                    });

                    LinearLayout.LayoutParams ilp = lp(-1, -2);
                    if (pos > 0) ilp.topMargin = dp(6);
                    list.addView(item, ilp);
                }
            }
        };

        resetChip.setOnClickListener(v -> {
            currentOrder.clear();
            for (int i = 0; i < totalPages; i++) currentOrder.add(i);
            swapSource[0] = -1;
            renderList.run();
        });
        reverseChip.setOnClickListener(v -> {
            Collections.reverse(currentOrder);
            swapSource[0] = -1;
            renderList.run();
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < currentOrder.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(currentOrder.get(i) + 1);
            }
            processPdf("reorder", sources, 0, sb.toString());
        });

        renderList.run();
        dialog.show();
    }

    private void showVisualRotateDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        HashSet<Integer> selectedPages = new HashSet<>();
        for (int i = 0; i < totalPages; i++) selectedPages.add(i);
        final int[] rotationAngle = {90};

        LinearLayout content = column();

        TextView angleLabel = text("Choose rotation angle:", 13, true, ink);
        content.addView(angleLabel);

        LinearLayout angleRow = row();
        TextView r90 = button("90° Clockwise", true, null);
        TextView r180 = button("180°", false, null);
        TextView r270 = button("270° (90° CCW)", false, null);
        for (TextView b : new TextView[]{r90, r180, r270}) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(40), 1);
            bp.leftMargin = dp(2);
            bp.rightMargin = dp(2);
            angleRow.addView(b, bp);
        }
        LinearLayout.LayoutParams alp = lp(-1, 40);
        alp.topMargin = dp(6);
        content.addView(angleRow, alp);

        LinearLayout chips = row();
        TextView allChip = button("Select All", false, null);
        TextView clearChip = button("Clear", false, null);
        for (TextView chip : new TextView[]{allChip, clearChip}) {
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(34), 1);
            cp.leftMargin = dp(2);
            cp.rightMargin = dp(2);
            chips.addView(chip, cp);
        }
        LinearLayout.LayoutParams chp = lp(-1, 34);
        chp.topMargin = dp(10);
        content.addView(chips, chp);

        LinearLayout grid = column();
        LinearLayout.LayoutParams gp = lp(-1, -2);
        gp.topMargin = dp(10);
        content.addView(grid, gp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Rotate Pages", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Rotate Pages",
            "Select pages and rotation angle",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        ArrayList<LinearLayout> cardViews = new ArrayList<>();
        ArrayList<ImageView> imageViews = new ArrayList<>();
        ArrayList<TextView> badgeViews = new ArrayList<>();

        Runnable updateUI = () -> {
            for (int i = 0; i < totalPages; i++) {
                boolean isSelected = selectedPages.contains(i);
                LinearLayout card = cardViews.get(i);
                TextView badge = badgeViews.get(i);
                ImageView thumb = imageViews.get(i);
                if (isSelected) {
                    card.setBackground(shape(isDark() ? Color.parseColor("#2A313C") : Color.parseColor("#EAF0FA"), 14, accent));
                    badge.setText("Page " + (i + 1) + " (↻ " + rotationAngle[0] + "°)");
                    badge.setTextColor(accent);
                    thumb.setRotation(rotationAngle[0]);
                } else {
                    card.setBackground(shape(surface, 14, line));
                    badge.setText("Page " + (i + 1));
                    badge.setTextColor(muted);
                    thumb.setRotation(0);
                }
            }
            int count = selectedPages.size();
            confirmBtn.setText(count > 0 ? "Rotate " + count + " " + (count == 1 ? "page" : "pages") + " by " + rotationAngle[0] + "°" : "Select pages to rotate");
            confirmBtn.setEnabled(count > 0);
            confirmBtn.setAlpha(count > 0 ? 1f : 0.5f);
        };

        r90.setOnClickListener(v -> {
            rotationAngle[0] = 90;
            r90.setBackground(shape(Color.parseColor("#245CCB"), 14, 0)); r90.setTextColor(Color.WHITE);
            r180.setBackground(shape(surface, 14, line)); r180.setTextColor(ink);
            r270.setBackground(shape(surface, 14, line)); r270.setTextColor(ink);
            updateUI.run();
        });
        r180.setOnClickListener(v -> {
            rotationAngle[0] = 180;
            r180.setBackground(shape(Color.parseColor("#245CCB"), 14, 0)); r180.setTextColor(Color.WHITE);
            r90.setBackground(shape(surface, 14, line)); r90.setTextColor(ink);
            r270.setBackground(shape(surface, 14, line)); r270.setTextColor(ink);
            updateUI.run();
        });
        r270.setOnClickListener(v -> {
            rotationAngle[0] = 270;
            r270.setBackground(shape(Color.parseColor("#245CCB"), 14, 0)); r270.setTextColor(Color.WHITE);
            r90.setBackground(shape(surface, 14, line)); r90.setTextColor(ink);
            r180.setBackground(shape(surface, 14, line)); r180.setTextColor(ink);
            updateUI.run();
        });

        allChip.setOnClickListener(v -> {
            selectedPages.clear();
            for (int i = 0; i < totalPages; i++) selectedPages.add(i);
            updateUI.run();
        });
        clearChip.setOnClickListener(v -> {
            selectedPages.clear();
            updateUI.run();
        });

        LinearLayout currentRow = null;
        for (int i = 0; i < totalPages; i++) {
            final int pageIdx = i;
            if (i % 2 == 0) {
                currentRow = row();
                LinearLayout.LayoutParams rp = lp(-1, -2);
                if (i > 0) rp.topMargin = dp(8);
                grid.addView(currentRow, rp);
            }
            LinearLayout card = column();
            pad(card, 8, 8, 8, 8);
            card.setBackground(shape(surface, 14, line));

            TextView badge = text("Page " + (i + 1), 11, true, muted);
            badge.setGravity(Gravity.CENTER);
            card.addView(badge, lp(-1, -2));

            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.FIT_CENTER);
            thumbnail.setBackground(shape(bg, 8, 0));
            LinearLayout.LayoutParams tp = lp(-1, 120);
            tp.topMargin = dp(6);
            card.addView(thumbnail, tp);

            cardViews.add(card);
            badgeViews.add(badge);
            imageViews.add(thumbnail);

            card.setOnClickListener(v -> {
                if (selectedPages.contains(pageIdx)) {
                    selectedPages.remove(pageIdx);
                } else {
                    selectedPages.add(pageIdx);
                }
                updateUI.run();
            });

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 != 0) cp.leftMargin = dp(8);
            currentRow.addView(card, cp);

            loadPdfPageThumbnail(sourceUri, pageIdx, 200, bmp -> {
                if (bmp != null) thumbnail.setImageBitmap(bmp);
            });
        }

        if (totalPages % 2 != 0 && currentRow != null) {
            View empty = new View(this);
            LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, 0, 1);
            ep.leftMargin = dp(8);
            currentRow.addView(empty, ep);
        }

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            StringBuilder sb = new StringBuilder();
            ArrayList<Integer> sorted = new ArrayList<>(selectedPages);
            Collections.sort(sorted);
            for (int p : sorted) {
                if (sb.length() > 0) sb.append(",");
                sb.append(p + 1);
            }
            processPdf("rotate", sources, rotationAngle[0], sb.toString());
        });

        updateUI.run();
        dialog.show();
    }

    private void showVisualCropDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        final int[] currentPage = {0};
        final float[] normCrop = {0.05f, 0.05f, 0.95f, 0.95f}; // left, top, right, bottom (0.0 to 1.0)

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setContentDescription("Previous page");
        prevPage.setBackground(shape(bg, 10, 0));
        prevPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(prevPage, lp(44, 44));

        TextView pageIndicator = text("Preview: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setContentDescription("Next page");
        nextPage.setBackground(shape(bg, 10, 0));
        nextPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(nextPage, lp(44, 44));

        content.addView(pagerRow, lp(-1, 44));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, line));
        LinearLayout.LayoutParams fp = lp(-1, dp(260));
        fp.topMargin = dp(8);
        content.addView(previewFrame, fp);

        FrameLayout pageCanvas = new FrameLayout(this);
        pageCanvas.setBackground(shape(Color.WHITE, 4, line));
        previewFrame.addView(pageCanvas, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_XY);
        pageCanvas.addView(pageImage, new FrameLayout.LayoutParams(-1, -1));

        TextView marginLabel = text("Crop Area: 90% × 90% (Free-form)", 13, true, ink);
        marginLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams mlp = lp(-1, -2);
        mlp.topMargin = dp(10);
        content.addView(marginLabel, mlp);

        TextView dragHint = text("Drag borders or corners to adjust crop box", 11, false, muted);
        dragHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dhlp = lp(-1, -2);
        dhlp.topMargin = dp(2);
        content.addView(dragHint, dhlp);

        View cropOverlay = new View(this) {
            private final Paint scrimPaint = new Paint();
            private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                scrimPaint.setColor(Color.argb(130, 0, 0, 0));
                borderPaint.setColor(accent);
                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(dp(2));
                gridPaint.setColor(Color.argb(70, 255, 255, 255));
                gridPaint.setStrokeWidth(dp(1));
            }

            private int activeHandle = -1; // 0: L, 1: T, 2: R, 3: B, 4: TL, 5: TR, 6: BL, 7: BR, 8: Inside
            private float lastTouchX, lastTouchY;

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;

                float pL = normCrop[0] * w;
                float pT = normCrop[1] * h;
                float pR = normCrop[2] * w;
                float pB = normCrop[3] * h;

                // Scrim
                canvas.drawRect(0, 0, w, pT, scrimPaint);
                canvas.drawRect(0, pB, w, h, scrimPaint);
                canvas.drawRect(0, pT, pL, pB, scrimPaint);
                canvas.drawRect(pR, pT, w, pB, scrimPaint);

                // Grid lines (Rule of Thirds)
                float boxW = pR - pL;
                float boxH = pB - pT;
                if (boxW > dp(30) && boxH > dp(30)) {
                    canvas.drawLine(pL + boxW / 3f, pT, pL + boxW / 3f, pB, gridPaint);
                    canvas.drawLine(pL + 2f * boxW / 3f, pT, pL + 2f * boxW / 3f, pB, gridPaint);
                    canvas.drawLine(pL, pT + boxH / 3f, pR, pT + boxH / 3f, gridPaint);
                    canvas.drawLine(pL, pT + 2f * boxH / 3f, pR, pT + 2f * boxH / 3f, gridPaint);
                }

                // Crop Border
                canvas.drawRect(pL, pT, pR, pB, borderPaint);

                // Corner Handles
                float cornerLen = dp(14);
                Paint cornerPaint = new Paint(borderPaint);
                cornerPaint.setStrokeWidth(dp(4));

                // TL
                canvas.drawLine(pL - dp(1), pT, pL + cornerLen, pT, cornerPaint);
                canvas.drawLine(pL, pT - dp(1), pL, pT + cornerLen, cornerPaint);
                // TR
                canvas.drawLine(pR - cornerLen, pT, pR + dp(1), pT, cornerPaint);
                canvas.drawLine(pR, pT - dp(1), pR, pT + cornerLen, cornerPaint);
                // BL
                canvas.drawLine(pL - dp(1), pB, pL + cornerLen, pB, cornerPaint);
                canvas.drawLine(pL, pB - cornerLen, pL, pB + dp(1), cornerPaint);
                // BR
                canvas.drawLine(pR - cornerLen, pB, pR + dp(1), pB, cornerPaint);
                canvas.drawLine(pR, pB - cornerLen, pR, pB + dp(1), cornerPaint);

                // Mid-Edge drag handle bars
                float midX = (pL + pR) / 2f;
                float midY = (pT + pB) / 2f;
                float edgeBarLen = dp(16);
                canvas.drawLine(midX - edgeBarLen / 2f, pT, midX + edgeBarLen / 2f, pT, cornerPaint);
                canvas.drawLine(midX - edgeBarLen / 2f, pB, midX + edgeBarLen / 2f, pB, cornerPaint);
                canvas.drawLine(pL, midY - edgeBarLen / 2f, pL, midY + edgeBarLen / 2f, cornerPaint);
                canvas.drawLine(pR, midY - edgeBarLen / 2f, pR, midY + edgeBarLen / 2f, cornerPaint);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return false;

                float x = event.getX();
                float y = event.getY();
                float pL = normCrop[0] * w;
                float pT = normCrop[1] * h;
                float pR = normCrop[2] * w;
                float pB = normCrop[3] * h;
                float touchTol = dp(24);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastTouchX = x;
                        lastTouchY = y;
                        activeHandle = -1;

                        if (Math.hypot(x - pL, y - pT) <= touchTol) activeHandle = 4;
                        else if (Math.hypot(x - pR, y - pT) <= touchTol) activeHandle = 5;
                        else if (Math.hypot(x - pL, y - pB) <= touchTol) activeHandle = 6;
                        else if (Math.hypot(x - pR, y - pB) <= touchTol) activeHandle = 7;
                        else if (Math.abs(x - pL) <= touchTol && y >= pT - touchTol && y <= pB + touchTol) activeHandle = 0;
                        else if (Math.abs(x - pR) <= touchTol && y >= pT - touchTol && y <= pB + touchTol) activeHandle = 2;
                        else if (Math.abs(y - pT) <= touchTol && x >= pL - touchTol && x <= pR + touchTol) activeHandle = 1;
                        else if (Math.abs(y - pB) <= touchTol && x >= pL - touchTol && x <= pR + touchTol) activeHandle = 3;
                        else if (x > pL && x < pR && y > pT && y < pB) activeHandle = 8;

                        if (activeHandle != -1) {
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        if (activeHandle == -1) return false;
                        float dx = (x - lastTouchX) / (float) w;
                        float dy = (y - lastTouchY) / (float) h;
                        lastTouchX = x;
                        lastTouchY = y;

                        float minDim = 0.10f;
                        if (activeHandle == 0 || activeHandle == 4 || activeHandle == 6) {
                            normCrop[0] = Math.max(0f, Math.min(normCrop[0] + dx, normCrop[2] - minDim));
                        }
                        if (activeHandle == 2 || activeHandle == 5 || activeHandle == 7) {
                            normCrop[2] = Math.min(1.0f, Math.max(normCrop[2] + dx, normCrop[0] + minDim));
                        }
                        if (activeHandle == 1 || activeHandle == 4 || activeHandle == 5) {
                            normCrop[1] = Math.max(0f, Math.min(normCrop[1] + dy, normCrop[3] - minDim));
                        }
                        if (activeHandle == 3 || activeHandle == 6 || activeHandle == 7) {
                            normCrop[3] = Math.min(1.0f, Math.max(normCrop[3] + dy, normCrop[1] + minDim));
                        }
                        if (activeHandle == 8) {
                            float boxW = normCrop[2] - normCrop[0];
                            float boxH = normCrop[3] - normCrop[1];
                            float newL = normCrop[0] + dx;
                            float newT = normCrop[1] + dy;
                            if (newL < 0f) newL = 0f;
                            if (newL + boxW > 1.0f) newL = 1.0f - boxW;
                            if (newT < 0f) newT = 0f;
                            if (newT + boxH > 1.0f) newT = 1.0f - boxH;
                            normCrop[0] = newL;
                            normCrop[1] = newT;
                            normCrop[2] = newL + boxW;
                            normCrop[3] = newT + boxH;
                        }

                        invalidate();
                        int pctW = Math.round((normCrop[2] - normCrop[0]) * 100f);
                        int pctH = Math.round((normCrop[3] - normCrop[1]) * 100f);
                        marginLabel.setText(String.format(Locale.getDefault(), "Crop Area: %d%% × %d%% (L:%.0f%%, T:%.0f%%, R:%.0f%%, B:%.0f%%)",
                            pctW, pctH, normCrop[0] * 100f, normCrop[1] * 100f, (1f - normCrop[2]) * 100f, (1f - normCrop[3]) * 100f));
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        activeHandle = -1;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                }
                return false;
            }
        };
        pageCanvas.addView(cropOverlay, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout presets = row();
        TextView fullBtn = button("Full / Reset", false, null);
        TextView squareBtn = button("Square (1:1)", false, null);
        TextView wideBtn = button("Banner (16:9)", false, null);
        for (TextView b : new TextView[]{fullBtn, squareBtn, wideBtn}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1);
            p.leftMargin = dp(2); p.rightMargin = dp(2);
            presets.addView(b, p);
        }
        LinearLayout.LayoutParams prp = lp(-1, 34);
        prp.topMargin = dp(8);
        content.addView(presets, prp);

        fullBtn.setOnClickListener(v -> {
            normCrop[0] = 0.02f; normCrop[1] = 0.02f; normCrop[2] = 0.98f; normCrop[3] = 0.98f;
            cropOverlay.invalidate();
            marginLabel.setText("Crop Area: 96% × 96% (Full Page)");
        });
        squareBtn.setOnClickListener(v -> {
            normCrop[0] = 0.15f; normCrop[1] = 0.15f; normCrop[2] = 0.85f; normCrop[3] = 0.85f;
            cropOverlay.invalidate();
            marginLabel.setText("Crop Area: 70% × 70% (Square 1:1)");
        });
        wideBtn.setOnClickListener(v -> {
            normCrop[0] = 0.05f; normCrop[1] = 0.25f; normCrop[2] = 0.95f; normCrop[3] = 0.75f;
            cropOverlay.invalidate();
            marginLabel.setText("Crop Area: 90% × 50% (Banner 16:9)");
        });

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Apply Crop to All Pages", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Crop PDF Pages",
            "Drag borders and corners to adjust crop area",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable loadPage = () -> {
            pageIndicator.setText("Preview: Page " + (currentPage[0] + 1) + " of " + totalPages);
            loadPdfPageThumbnail(sourceUri, currentPage[0], 400, bmp -> {
                if (bmp != null) {
                    pageImage.setImageBitmap(bmp);
                    int maxW = getResources().getDisplayMetrics().widthPixels - dp(64);
                    int maxH = dp(240);
                    float scale = Math.min((float) maxW / bmp.getWidth(), (float) maxH / bmp.getHeight());
                    int cW = Math.max(1, Math.round(bmp.getWidth() * scale));
                    int cH = Math.max(1, Math.round(bmp.getHeight() * scale));
                    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cW, cH, Gravity.CENTER);
                    pageCanvas.setLayoutParams(cp);
                    cropOverlay.invalidate();
                }
            });
        };

        prevPage.setOnClickListener(v -> {
            if (currentPage[0] > 0) {
                currentPage[0]--;
                loadPage.run();
            }
        });
        nextPage.setOnClickListener(v -> {
            if (currentPage[0] < totalPages - 1) {
                currentPage[0]++;
                loadPage.run();
            }
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            String cropParam = normCrop[0] + ":" + normCrop[1] + ":" + normCrop[2] + ":" + normCrop[3];
            processPdf("crop", sources, 0, cropParam);
        });

        loadPage.run();
        dialog.show();
    }

    private interface ImageCropCallback {
        void onCropped(Bitmap cropped, float[] finalNormCrop);
    }

    private void applyCropPreset(float[] normCrop, int srcW, int srcH, String preset) {
        if ("full".equals(preset)) {
            normCrop[0] = 0f; normCrop[1] = 0f; normCrop[2] = 1f; normCrop[3] = 1f;
            return;
        }
        if ("50%".equals(preset)) {
            normCrop[0] = 0.25f; normCrop[1] = 0.25f; normCrop[2] = 0.75f; normCrop[3] = 0.75f;
            return;
        }
        if ("80%".equals(preset)) {
            normCrop[0] = 0.10f; normCrop[1] = 0.10f; normCrop[2] = 0.90f; normCrop[3] = 0.90f;
            return;
        }

        float targetRatio;
        if ("1:1".equals(preset)) targetRatio = 1.0f;
        else if ("4:5".equals(preset)) targetRatio = 4f / 5f;
        else if ("16:9".equals(preset)) targetRatio = 16f / 9f;
        else if ("4:3".equals(preset)) targetRatio = 4f / 3f;
        else if ("3:2".equals(preset)) targetRatio = 3f / 2f;
        else if ("9:16".equals(preset)) targetRatio = 9f / 16f;
        else return;

        float imgRatio = (float) srcW / (float) srcH;
        float boxNormW, boxNormH;

        if (targetRatio > imgRatio) {
            boxNormW = 0.96f;
            boxNormH = Math.min(0.96f, (boxNormW * imgRatio) / targetRatio);
        } else {
            boxNormH = 0.96f;
            boxNormW = Math.min(0.96f, (boxNormH * targetRatio) / imgRatio);
        }

        normCrop[0] = (1.0f - boxNormW) / 2f;
        normCrop[1] = (1.0f - boxNormH) / 2f;
        normCrop[2] = normCrop[0] + boxNormW;
        normCrop[3] = normCrop[1] + boxNormH;
    }

    private void showInteractiveImageCropDialog(Bitmap srcBitmap, float[] initialNormCrop, ImageCropCallback callback) {
        if (srcBitmap == null || srcBitmap.getWidth() <= 0 || srcBitmap.getHeight() <= 0) {
            toast("No image available to crop");
            return;
        }

        final int srcW = srcBitmap.getWidth();
        final int srcH = srcBitmap.getHeight();

        final float[] normCrop = new float[]{0.02f, 0.02f, 0.98f, 0.98f};
        if (initialNormCrop != null && initialNormCrop.length == 4) {
            normCrop[0] = Math.max(0f, Math.min(initialNormCrop[0], 0.9f));
            normCrop[1] = Math.max(0f, Math.min(initialNormCrop[1], 0.9f));
            normCrop[2] = Math.min(1f, Math.max(initialNormCrop[2], normCrop[0] + 0.05f));
            normCrop[3] = Math.min(1f, Math.max(initialNormCrop[3], normCrop[1] + 0.05f));
        }

        final String[] currentPreset = {"free"};

        LinearLayout content = column();

        TextView hintLabel = text("Drag corners/edges to crop freely, or choose a preset:", 12, false, muted);
        content.addView(hintLabel, lp(-1, -2));

        TextView cropDimLabel = text("Crop: " + srcW + " × " + srcH + " (Full Image)", 12, true, ink);
        LinearLayout.LayoutParams clp = lp(-1, -2);
        clp.topMargin = dp(4);
        content.addView(cropDimLabel, clp);

        FrameLayout cropFrame = new FrameLayout(this);
        cropFrame.setBackground(shape(bg, 14, line));
        LinearLayout.LayoutParams cflp = lp(-1, dp(260));
        cflp.topMargin = dp(8);
        content.addView(cropFrame, cflp);

        FrameLayout canvasHolder = new FrameLayout(this);
        canvasHolder.setBackground(shape(Color.TRANSPARENT, 0, 0));
        cropFrame.addView(canvasHolder, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        ImageView previewImg = new ImageView(this);
        previewImg.setScaleType(ImageView.ScaleType.FIT_XY);
        previewImg.setImageBitmap(srcBitmap);
        canvasHolder.addView(previewImg, new FrameLayout.LayoutParams(-1, -1));

        View cropOverlay = new View(this) {
            private final Paint shadePaint = new Paint();
            private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint handleFill = new Paint(Paint.ANTI_ALIAS_FLAG);

            {
                shadePaint.setColor(Color.parseColor("#99000000"));
                shadePaint.setStyle(Paint.Style.FILL);

                borderPaint.setColor(Color.WHITE);
                borderPaint.setStrokeWidth(dp(2));
                borderPaint.setStyle(Paint.Style.STROKE);

                gridPaint.setColor(Color.parseColor("#66FFFFFF"));
                gridPaint.setStrokeWidth(dp(1));
                gridPaint.setStyle(Paint.Style.STROKE);

                handlePaint.setColor(Color.WHITE);
                handlePaint.setStrokeWidth(dp(3.5f));
                handlePaint.setStyle(Paint.Style.STROKE);
                handlePaint.setStrokeCap(Paint.Cap.ROUND);

                handleFill.setColor(Color.parseColor("#245CCB"));
                handleFill.setStyle(Paint.Style.FILL);
            }

            private float lastTouchX = 0, lastTouchY = 0;
            private int activeHandle = -1;

            private final ScaleGestureDetector pinchDetector = new ScaleGestureDetector(getContext(), new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    if (factor > 0.1f && factor < 5.0f) {
                        float cx = (normCrop[0] + normCrop[2]) / 2f;
                        float cy = (normCrop[1] + normCrop[3]) / 2f;
                        float halfW = (normCrop[2] - normCrop[0]) / 2f * factor;
                        float halfH = (normCrop[3] - normCrop[1]) / 2f * factor;
                        float minHalf = 0.05f;
                        halfW = Math.max(minHalf, halfW);
                        halfH = Math.max(minHalf, halfH);

                        float nL = Math.max(0f, cx - halfW);
                        float nT = Math.max(0f, cy - halfH);
                        float nR = Math.min(1f, cx + halfW);
                        float nB = Math.min(1f, cy + halfH);

                        normCrop[0] = nL;
                        normCrop[1] = nT;
                        normCrop[2] = nR;
                        normCrop[3] = nB;

                        updateDimLabel();
                        invalidate();
                        return true;
                    }
                    return false;
                }
            });

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return;

                float pL = normCrop[0] * w;
                float pT = normCrop[1] * h;
                float pR = normCrop[2] * w;
                float pB = normCrop[3] * h;

                canvas.drawRect(0, 0, w, pT, shadePaint);
                canvas.drawRect(0, pB, w, h, shadePaint);
                canvas.drawRect(0, pT, pL, pB, shadePaint);
                canvas.drawRect(pR, pT, w, pB, shadePaint);

                canvas.drawRect(pL, pT, pR, pB, borderPaint);

                float w3 = (pR - pL) / 3f;
                float h3 = (pB - pT) / 3f;
                canvas.drawLine(pL + w3, pT, pL + w3, pB, gridPaint);
                canvas.drawLine(pL + 2 * w3, pT, pL + 2 * w3, pB, gridPaint);
                canvas.drawLine(pL, pT + h3, pR, pT + h3, gridPaint);
                canvas.drawLine(pL, pT + 2 * h3, pR, pT + 2 * h3, gridPaint);

                float cLen = dp(18);
                canvas.drawLine(pL - dp(1), pT, pL + cLen, pT, handlePaint);
                canvas.drawLine(pL, pT - dp(1), pL, pT + cLen, handlePaint);

                canvas.drawLine(pR - cLen, pT, pR + dp(1), pT, handlePaint);
                canvas.drawLine(pR, pT - dp(1), pR, pT + cLen, handlePaint);

                canvas.drawLine(pL - dp(1), pB, pL + cLen, pB, handlePaint);
                canvas.drawLine(pL, pB - cLen, pL, pB + dp(1), handlePaint);

                canvas.drawLine(pR - cLen, pB, pR + dp(1), pB, handlePaint);
                canvas.drawLine(pR, pB - cLen, pR, pB + dp(1), handlePaint);

                float radius = dp(4);
                canvas.drawCircle(pL, pT, radius, handleFill);
                canvas.drawCircle(pR, pT, radius, handleFill);
                canvas.drawCircle(pL, pB, radius, handleFill);
                canvas.drawCircle(pR, pB, radius, handleFill);

                float midX = (pL + pR) / 2f;
                float midY = (pT + pB) / 2f;
                float barLen = dp(16);
                canvas.drawLine(midX - barLen / 2f, pT, midX + barLen / 2f, pT, handlePaint);
                canvas.drawLine(midX - barLen / 2f, pB, midX + barLen / 2f, pB, handlePaint);
                canvas.drawLine(pL, midY - barLen / 2f, pL, midY + barLen / 2f, handlePaint);
                canvas.drawLine(pR, midY - barLen / 2f, pR, midY + barLen / 2f, handlePaint);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                pinchDetector.onTouchEvent(event);
                if (pinchDetector.isInProgress()) {
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }

                int w = getWidth();
                int h = getHeight();
                if (w <= 0 || h <= 0) return false;

                float x = event.getX();
                float y = event.getY();
                float pL = normCrop[0] * w;
                float pT = normCrop[1] * h;
                float pR = normCrop[2] * w;
                float pB = normCrop[3] * h;
                float tol = dp(28);

                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        lastTouchX = x;
                        lastTouchY = y;
                        activeHandle = -1;

                        if (Math.hypot(x - pL, y - pT) <= tol) activeHandle = 4;
                        else if (Math.hypot(x - pR, y - pT) <= tol) activeHandle = 5;
                        else if (Math.hypot(x - pL, y - pB) <= tol) activeHandle = 6;
                        else if (Math.hypot(x - pR, y - pB) <= tol) activeHandle = 7;
                        else if (Math.abs(x - pL) <= tol && y >= pT - tol && y <= pB + tol) activeHandle = 0;
                        else if (Math.abs(x - pR) <= tol && y >= pT - tol && y <= pB + tol) activeHandle = 2;
                        else if (Math.abs(y - pT) <= tol && x >= pL - tol && x <= pR + tol) activeHandle = 1;
                        else if (Math.abs(y - pB) <= tol && x >= pL - tol && x <= pR + tol) activeHandle = 3;
                        else if (x > pL && x < pR && y > pT && y < pB) activeHandle = 8;

                        if (activeHandle != -1) {
                            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                        return false;

                    case MotionEvent.ACTION_MOVE:
                        if (activeHandle == -1) return false;
                        float dx = (x - lastTouchX) / (float) w;
                        float dy = (y - lastTouchY) / (float) h;
                        lastTouchX = x;
                        lastTouchY = y;

                        float minDim = 0.08f;
                        if (activeHandle == 0 || activeHandle == 4 || activeHandle == 6) {
                            normCrop[0] = Math.max(0f, Math.min(normCrop[0] + dx, normCrop[2] - minDim));
                        }
                        if (activeHandle == 2 || activeHandle == 5 || activeHandle == 7) {
                            normCrop[2] = Math.min(1.0f, Math.max(normCrop[2] + dx, normCrop[0] + minDim));
                        }
                        if (activeHandle == 1 || activeHandle == 4 || activeHandle == 5) {
                            normCrop[1] = Math.max(0f, Math.min(normCrop[1] + dy, normCrop[3] - minDim));
                        }
                        if (activeHandle == 3 || activeHandle == 6 || activeHandle == 7) {
                            normCrop[3] = Math.min(1.0f, Math.max(normCrop[3] + dy, normCrop[1] + minDim));
                        }
                        if (activeHandle == 8) {
                            float boxW = normCrop[2] - normCrop[0];
                            float boxH = normCrop[3] - normCrop[1];
                            float newL = normCrop[0] + dx;
                            float newT = normCrop[1] + dy;
                            if (newL < 0f) newL = 0f;
                            if (newL + boxW > 1.0f) newL = 1.0f - boxW;
                            if (newT < 0f) newT = 0f;
                            if (newT + boxH > 1.0f) newT = 1.0f - boxH;
                            normCrop[0] = newL;
                            normCrop[1] = newT;
                            normCrop[2] = newL + boxW;
                            normCrop[3] = newT + boxH;
                        }

                        updateDimLabel();
                        invalidate();
                        return true;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        activeHandle = -1;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                }
                return false;
            }

            private void updateDimLabel() {
                int cW = Math.max(1, Math.round((normCrop[2] - normCrop[0]) * srcW));
                int cH = Math.max(1, Math.round((normCrop[3] - normCrop[1]) * srcH));
                int pct = Math.round((normCrop[2] - normCrop[0]) * (normCrop[3] - normCrop[1]) * 100f);
                cropDimLabel.setText(String.format(Locale.getDefault(), "Crop: %d × %d · %d%% of image", cW, cH, pct));
            }
        };
        canvasHolder.addView(cropOverlay, new FrameLayout.LayoutParams(-1, -1));

        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout presetRow = row();
        presetRow.setPadding(0, dp(4), 0, dp(4));

        String[][] presetsList = new String[][]{
            {"Free (Custom)", "free"},
            {"1:1 Square", "1:1"},
            {"4:5 Portrait", "4:5"},
            {"16:9 Banner", "16:9"},
            {"4:3 Standard", "4:3"},
            {"3:2 Photo", "3:2"},
            {"9:16 Story", "9:16"},
            {"50% Center", "50%"},
            {"80% Center", "80%"},
            {"Full / Reset", "full"}
        };

        ArrayList<TextView> chipViews = new ArrayList<>();
        for (String[] p : presetsList) {
            final String label = p[0];
            final String key = p[1];
            TextView chipView = chip(label, key.equals(currentPreset[0]), null);
            chipView.setOnClickListener(v -> {
                currentPreset[0] = key;
                if (!"free".equals(key)) {
                    applyCropPreset(normCrop, srcW, srcH, key);
                }
                for (int ci = 0; ci < chipViews.size(); ci++) {
                    boolean isSel = presetsList[ci][1].equals(currentPreset[0]);
                    chipViews.get(ci).setBackground(shape(isSel ? accent : surface, 12, isSel ? accent : line));
                    chipViews.get(ci).setTextColor(isSel ? Color.WHITE : ink);
                }
                int cW = Math.max(1, Math.round((normCrop[2] - normCrop[0]) * srcW));
                int cH = Math.max(1, Math.round((normCrop[3] - normCrop[1]) * srcH));
                int pct = Math.round((normCrop[2] - normCrop[0]) * (normCrop[3] - normCrop[1]) * 100f);
                cropDimLabel.setText(String.format(Locale.getDefault(), "Crop: %d × %d (%s) · %d%% of image", cW, cH, label, pct));
                cropOverlay.invalidate();
            });
            LinearLayout.LayoutParams chp = lp(-2, dp(34));
            chp.rightMargin = dp(6);
            presetRow.addView(chipView, chp);
            chipViews.add(chipView);
        }
        presetScroll.addView(presetRow, lp(-2, -2));
        LinearLayout.LayoutParams pslp = lp(-1, -2);
        pslp.topMargin = dp(8);
        content.addView(presetScroll, pslp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView applyBtn = button("Apply Crop", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(48), 1));
        LinearLayout.LayoutParams apParams = new LinearLayout.LayoutParams(0, dp(48), 1);
        apParams.leftMargin = dp(8);
        bottomRow.addView(applyBtn, apParams);

        Dialog dialog = createVisualDialog(
            "Crop Image",
            "Free/Manual crop by dragging corners or choose presets",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        applyBtn.setOnClickListener(v -> {
            dialog.dismiss();
            int x = Math.max(0, Math.round(normCrop[0] * srcW));
            int y = Math.max(0, Math.round(normCrop[1] * srcH));
            int w = Math.min(srcW - x, Math.max(1, Math.round((normCrop[2] - normCrop[0]) * srcW)));
            int h = Math.min(srcH - y, Math.max(1, Math.round((normCrop[3] - normCrop[1]) * srcH)));

            try {
                Bitmap cropped = Bitmap.createBitmap(srcBitmap, x, y, w, h);
                callback.onCropped(cropped, normCrop);
            } catch (Exception e) {
                toast("Could not crop image: " + (e.getMessage() != null ? e.getMessage() : "Error"));
            }
        });

        Runnable layoutCanvas = () -> {
            int maxW = getResources().getDisplayMetrics().widthPixels - dp(64);
            int maxH = dp(240);
            float scale = Math.min((float) maxW / srcW, (float) maxH / srcH);
            int cW = Math.max(1, Math.round(srcW * scale));
            int cH = Math.max(1, Math.round(srcH * scale));
            FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cW, cH, Gravity.CENTER);
            canvasHolder.setLayoutParams(cp);
            cropOverlay.invalidate();
        };
        canvasHolder.post(layoutCanvas);

        dialog.show();
    }

    private Bitmap cropImageBitmap(Bitmap src, String mode) {
        if (src == null) return null;
        int w = src.getWidth();
        int h = src.getHeight();
        if (w <= 0 || h <= 0) return src;
        int targetW = w, targetH = h;
        if (mode.equals("50%")) {
            targetW = Math.max(1, Math.round(w * 0.5f));
            targetH = Math.max(1, Math.round(h * 0.5f));
        } else if (mode.equals("80%")) {
            targetW = Math.max(1, Math.round(w * 0.8f));
            targetH = Math.max(1, Math.round(h * 0.8f));
        } else if (mode.equals("1:1")) {
            int s = Math.min(w, h);
            targetW = s;
            targetH = s;
        } else if (mode.equals("16:9")) {
            if (w * 9 > h * 16) {
                targetW = Math.max(1, Math.round(h * 16f / 9f));
                targetH = h;
            } else {
                targetW = w;
                targetH = Math.max(1, Math.round(w * 9f / 16f));
            }
        } else if (mode.equals("4:3")) {
            if (w * 3 > h * 4) {
                targetW = Math.max(1, Math.round(h * 4f / 3f));
                targetH = h;
            } else {
                targetW = w;
                targetH = Math.max(1, Math.round(w * 3f / 4f));
            }
        }
        int startX = Math.max(0, (w - targetW) / 2);
        int startY = Math.max(0, (h - targetH) / 2);
        return Bitmap.createBitmap(src, startX, startY, targetW, targetH);
    }

    private void showVisualAddImageDialog(ArrayList<Uri> sources, int totalPages, Uri imageUri, Bitmap originalBitmap) {
        Uri sourceUri = sources.get(0);
        final int[] targetPage = {0};
        final Bitmap[] currentWorkingBitmap = {originalBitmap};
        final float[] normX = {0.25f};
        final float[] normY = {0.25f};
        final float[] normW = {0.50f};
        final float[] normH = {0.50f};
        final int[] imagePercent = {40};
        final boolean[] maintainRatio = {true};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setContentDescription("Previous page");
        prevPage.setBackground(shape(bg, 10, 0));
        prevPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(prevPage, lp(44, 44));

        TextView pageIndicator = text("Insert onto: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setContentDescription("Next page");
        nextPage.setBackground(shape(bg, 10, 0));
        nextPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(nextPage, lp(44, 44));

        content.addView(pagerRow, lp(-1, 44));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, line));
        LinearLayout.LayoutParams fp = lp(-1, dp(250));
        fp.topMargin = dp(8);
        content.addView(previewFrame, fp);

        FrameLayout pageCanvas = new FrameLayout(this);
        pageCanvas.setBackground(shape(Color.WHITE, 4, line));
        previewFrame.addView(pageCanvas, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_XY);
        pageCanvas.addView(pageImage, new FrameLayout.LayoutParams(-1, -1));

        ImageView overlayImage = new ImageView(this);
        overlayImage.setScaleType(ImageView.ScaleType.FIT_XY);
        overlayImage.setImageBitmap(currentWorkingBitmap[0]);
        overlayImage.setBackground(shape(Color.TRANSPARENT, 2, Color.parseColor("#245CCB")));
        pageCanvas.addView(overlayImage, new FrameLayout.LayoutParams(dp(80), dp(80)));

        TextView dragHint = text("Drag image to reposition on page", 11, false, muted);
        dragHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dhlp = lp(-1, -2);
        dhlp.topMargin = dp(4);
        content.addView(dragHint, dhlp);

        LinearLayout posRow = row();
        String[] posNames = {"Center", "Top-L", "Top-R", "Bot-L", "Bot-R"};
        for (int i = 0; i < posNames.length; i++) {
            final int posIdx = i;
            TextView pb = button(posNames[i], false, null);
            pb.setTextSize(12);
            pb.setMinHeight(dp(36));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(36), 1);
            if (i > 0) p.leftMargin = dp(4);
            posRow.addView(pb, p);

            pb.setOnClickListener(v -> {
                int cW = pageCanvas.getWidth();
                int cH = pageCanvas.getHeight();
                int oW = overlayImage.getWidth();
                int oH = overlayImage.getHeight();
                if (cW <= 0 || cH <= 0) return;
                float nx = 0, ny = 0;
                if (posIdx == 0) {
                    nx = (cW - oW) / 2f;
                    ny = (cH - oH) / 2f;
                } else if (posIdx == 1) {
                    nx = 0; ny = 0;
                } else if (posIdx == 2) {
                    nx = cW - oW; ny = 0;
                } else if (posIdx == 3) {
                    nx = 0; ny = cH - oH;
                } else if (posIdx == 4) {
                    nx = cW - oW; ny = cH - oH;
                }
                overlayImage.setX(nx);
                overlayImage.setY(ny);
                normX[0] = nx / (float) cW;
                normY[0] = ny / (float) cH;
            });
        }
        LinearLayout.LayoutParams prp = lp(-1, -2);
        prp.topMargin = dp(8);
        content.addView(posRow, prp);

        TextView sizeLabel = text("Image size: " + imagePercent[0] + "%", 13, true, ink);
        LinearLayout.LayoutParams slp = lp(-1, -2);
        slp.topMargin = dp(10);
        content.addView(sizeLabel, slp);

        SeekBar sizeBar = new SeekBar(this);
        sizeBar.setMax(95);
        sizeBar.setProgress(imagePercent[0] - 5);
        content.addView(sizeBar, lp(-1, 38));

        CheckBox cbAspect = new CheckBox(this);
        cbAspect.setText("Maintain aspect ratio");
        cbAspect.setTextColor(ink);
        cbAspect.setChecked(true);
        cbAspect.setTextSize(13);
        content.addView(cbAspect, lp(-1, -2));

        LinearLayout cropRow = row();
        TextView btnCrop = button("Crop Image", false, null);
        btnCrop.setMinHeight(dp(40));
        TextView btnReset = button("Reset Image", false, null);
        btnReset.setMinHeight(dp(40));
        cropRow.addView(btnCrop, new LinearLayout.LayoutParams(0, dp(40), 1));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(0, dp(40), 1);
        rlp.leftMargin = dp(8);
        cropRow.addView(btnReset, rlp);
        LinearLayout.LayoutParams crp = lp(-1, -2);
        crp.topMargin = dp(8);
        content.addView(cropRow, crp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Insert onto Page 1", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Insert Image into PDF",
            "Visually position, resize, and crop image",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable updateOverlay = () -> {
            int cW = pageCanvas.getWidth();
            int cH = pageCanvas.getHeight();
            Bitmap cur = currentWorkingBitmap[0];
            if (cW <= 0 || cH <= 0 || cur == null || cur.getWidth() <= 0 || cur.getHeight() <= 0) return;

            int imgW = Math.max(dp(20), Math.round(cW * imagePercent[0] / 100f));
            int imgH;
            if (maintainRatio[0]) {
                float ratio = (float) cur.getHeight() / cur.getWidth();
                imgH = Math.max(dp(20), Math.round(imgW * ratio));
                if (imgH > cH) {
                    float scaleDown = (float) cH / imgH;
                    imgH = cH;
                    imgW = Math.max(dp(20), Math.round(imgW * scaleDown));
                }
            } else {
                imgH = Math.max(dp(20), Math.round(cH * imagePercent[0] / 100f));
            }

            FrameLayout.LayoutParams olp = (FrameLayout.LayoutParams) overlayImage.getLayoutParams();
            olp.width = imgW;
            olp.height = imgH;
            overlayImage.setLayoutParams(olp);

            normW[0] = imgW / (float) cW;
            normH[0] = imgH / (float) cH;

            int maxW = cW - imgW;
            int maxH = cH - imgH;
            if (maxW < 0) maxW = 0;
            if (maxH < 0) maxH = 0;

            float curX = normX[0] * cW;
            float curY = normY[0] * cH;
            curX = Math.max(0, Math.min(curX, maxW));
            curY = Math.max(0, Math.min(curY, maxH));

            overlayImage.setX(curX);
            overlayImage.setY(curY);

            normX[0] = curX / (float) cW;
            normY[0] = curY / (float) cH;
        };

        ScaleGestureDetector scaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float scaleFactor = detector.getScaleFactor();
                int newPercent = Math.round(imagePercent[0] * scaleFactor);
                newPercent = Math.max(5, Math.min(100, newPercent));
                if (newPercent != imagePercent[0]) {
                    imagePercent[0] = newPercent;
                    sizeBar.setProgress(imagePercent[0] - 5);
                    sizeLabel.setText("Image size: " + imagePercent[0] + "%");
                    updateOverlay.run();
                }
                return true;
            }
        });

        overlayImage.setOnTouchListener(new View.OnTouchListener() {
            float dX, dY;
            boolean dragging = false;
            public boolean onTouch(View v, MotionEvent event) {
                scaleDetector.onTouchEvent(event);
                if (scaleDetector.isInProgress()) {
                    dragging = false;
                    return true;
                }
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        dX = v.getX() - event.getRawX();
                        dY = v.getY() - event.getRawY();
                        dragging = true;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (dragging && !scaleDetector.isInProgress() && pageCanvas.getWidth() > 0 && pageCanvas.getHeight() > 0) {
                            float newX = event.getRawX() + dX;
                            float newY = event.getRawY() + dY;
                            int maxW = pageCanvas.getWidth() - v.getWidth();
                            int maxH = pageCanvas.getHeight() - v.getHeight();
                            if (maxW < 0) maxW = 0;
                            if (maxH < 0) maxH = 0;
                            newX = Math.max(0, Math.min(newX, maxW));
                            newY = Math.max(0, Math.min(newY, maxH));
                            v.setX(newX);
                            v.setY(newY);
                            normX[0] = newX / (float) pageCanvas.getWidth();
                            normY[0] = newY / (float) pageCanvas.getHeight();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        dragging = false;
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(false);
                        return true;
                }
                return false;
            }
        });

        sizeBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {}
            public void onProgressChanged(SeekBar bar, int progress, boolean user) {
                imagePercent[0] = 5 + progress;
                sizeLabel.setText("Image size: " + imagePercent[0] + "%");
                updateOverlay.run();
            }
        });

        cbAspect.setOnCheckedChangeListener((buttonView, isChecked) -> {
            maintainRatio[0] = isChecked;
            updateOverlay.run();
        });

        final float[] lastNormCrop = new float[]{0f, 0f, 1f, 1f};

        btnCrop.setOnClickListener(v -> {
            showInteractiveImageCropDialog(originalBitmap, lastNormCrop, (cropped, newNormCrop) -> {
                if (cropped != null) {
                    System.arraycopy(newNormCrop, 0, lastNormCrop, 0, 4);
                    currentWorkingBitmap[0] = cropped;
                    overlayImage.setImageBitmap(cropped);
                    updateOverlay.run();
                    toast("✓ Image cropped");
                }
            });
        });

        btnReset.setOnClickListener(v -> {
            lastNormCrop[0] = 0f; lastNormCrop[1] = 0f; lastNormCrop[2] = 1f; lastNormCrop[3] = 1f;
            currentWorkingBitmap[0] = originalBitmap;
            overlayImage.setImageBitmap(originalBitmap);
            updateOverlay.run();
            toast("Image reset to original");
        });

        Runnable loadPage = () -> {
            pageIndicator.setText("Insert onto: Page " + (targetPage[0] + 1) + " of " + totalPages);
            confirmBtn.setText("Insert onto Page " + (targetPage[0] + 1));
            loadPdfPageThumbnail(sourceUri, targetPage[0], 400, bmp -> {
                if (bmp != null) {
                    pageImage.setImageBitmap(bmp);
                    int maxW = getResources().getDisplayMetrics().widthPixels - dp(64);
                    int maxH = dp(230);
                    float scale = Math.min((float) maxW / bmp.getWidth(), (float) maxH / bmp.getHeight());
                    int cW = Math.max(1, Math.round(bmp.getWidth() * scale));
                    int cH = Math.max(1, Math.round(bmp.getHeight() * scale));
                    FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(cW, cH, Gravity.CENTER);
                    pageCanvas.setLayoutParams(cp);
                    pageCanvas.post(updateOverlay);
                }
            });
        };

        prevPage.setOnClickListener(v -> {
            if (targetPage[0] > 0) {
                targetPage[0]--;
                loadPage.run();
            }
        });
        nextPage.setOnClickListener(v -> {
            if (targetPage[0] < totalPages - 1) {
                targetPage[0]++;
                loadPage.run();
            }
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            toast("Adding image to PDF...");
            if (progressText != null) progressText.setText("Adding image to PDF...");
            worker.execute(() -> {
                try {
                    File dir = new File(getCacheDir(), "fileswitch");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output directory");
                    File tempImgFile = new File(dir, "temp-insert-" + UUID.randomUUID() + ".png");
                    try (FileOutputStream fos = new FileOutputStream(tempImgFile)) {
                        currentWorkingBitmap[0].compress(Bitmap.CompressFormat.PNG, 100, fos);
                    }
                    Uri tempImgUri = Uri.fromFile(tempImgFile);
                    ArrayList<Uri> finalSources = new ArrayList<>();
                    finalSources.add(sources.get(0));
                    finalSources.add(tempImgUri);
                    String coordVal = (targetPage[0] + 1) + ":" + normX[0] + ":" + normY[0] + ":" + normW[0] + ":" + normH[0];
                    processPdf("addImage", finalSources, 0, coordVal);
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Failed to insert image: " + (e.getMessage() != null ? e.getMessage() : "Unknown error")));
                }
            });
        });

        loadPage.run();
        dialog.show();
    }

    private void showVisualStampDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        final int[] targetPage = {0};
        final int[] stampPos = {0};
        final String[] stampText = {"CONFIDENTIAL"};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setContentDescription("Previous page");
        prevPage.setBackground(shape(bg, 10, 0));
        prevPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(prevPage, lp(44, 44));

        TextView pageIndicator = text("Preview on: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setContentDescription("Next page");
        nextPage.setBackground(shape(bg, 10, 0));
        nextPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(nextPage, lp(44, 44));

        content.addView(pagerRow, lp(-1, 44));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, 0));
        LinearLayout.LayoutParams fp = lp(-1, 240);
        fp.topMargin = dp(10);
        content.addView(previewFrame, fp);

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        TextView watermarkOverlay = text("CONFIDENTIAL", 22, true, Color.argb(180, 210, 30, 30));
        watermarkOverlay.setGravity(Gravity.CENTER);
        watermarkOverlay.setRotation(-30f);
        previewFrame.addView(watermarkOverlay, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        TextView textLabel = text("Stamp text / Preset:", 13, true, ink);
        LinearLayout.LayoutParams tlp = lp(-1, -2);
        tlp.topMargin = dp(12);
        content.addView(textLabel, tlp);

        EditText customInput = new EditText(this);
        customInput.setText("CONFIDENTIAL");
        customInput.setTextSize(14);
        customInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleEditText(customInput);
        content.addView(customInput, lp(-1, 46));

        LinearLayout presets = row();
        String[] presetList = {"CONFIDENTIAL", "APPROVED", "DRAFT", "COPY", "OFFICIAL"};
        for (String p : presetList) {
            TextView pb = button(p, false, null);
            pb.setOnClickListener(v -> {
                customInput.setText(p);
                stampText[0] = p;
                watermarkOverlay.setText(p);
            });
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(34), 1);
            pp.leftMargin = dp(2); pp.rightMargin = dp(2);
            presets.addView(pb, pp);
        }
        LinearLayout.LayoutParams prp = lp(-1, 34);
        prp.topMargin = dp(8);
        content.addView(presets, prp);

        TextView posLabel = text("Placement style:", 13, true, ink);
        LinearLayout.LayoutParams poslp = lp(-1, -2);
        poslp.topMargin = dp(10);
        content.addView(posLabel, poslp);

        LinearLayout posRow = row();
        TextView pDiag = button("Diagonal", true, null);
        TextView pTop = button("Top Banner", false, null);
        TextView pBot = button("Bottom", false, null);
        for (TextView pb : new TextView[]{pDiag, pTop, pBot}) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1);
            p.leftMargin = dp(2); p.rightMargin = dp(2);
            posRow.addView(pb, p);
        }
        LinearLayout.LayoutParams posrp = lp(-1, 34);
        posrp.topMargin = dp(6);
        content.addView(posRow, posrp);

        pDiag.setOnClickListener(v -> {
            stampPos[0] = 0;
            pDiag.setBackground(shape(Color.parseColor("#245CCB"), 12, 0)); pDiag.setTextColor(Color.WHITE);
            pTop.setBackground(shape(surface, 12, line)); pTop.setTextColor(ink);
            pBot.setBackground(shape(surface, 12, line)); pBot.setTextColor(ink);
            watermarkOverlay.setRotation(-30f);
            watermarkOverlay.setTextSize(22);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) watermarkOverlay.getLayoutParams();
            p.gravity = Gravity.CENTER;
            p.setMargins(0, 0, 0, 0);
            watermarkOverlay.setLayoutParams(p);
        });
        pTop.setOnClickListener(v -> {
            stampPos[0] = 1;
            pTop.setBackground(shape(Color.parseColor("#245CCB"), 12, 0)); pTop.setTextColor(Color.WHITE);
            pDiag.setBackground(shape(surface, 12, line)); pDiag.setTextColor(ink);
            pBot.setBackground(shape(surface, 12, line)); pBot.setTextColor(ink);
            watermarkOverlay.setRotation(0f);
            watermarkOverlay.setTextSize(14);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) watermarkOverlay.getLayoutParams();
            p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            p.setMargins(0, dp(14), 0, 0);
            watermarkOverlay.setLayoutParams(p);
        });
        pBot.setOnClickListener(v -> {
            stampPos[0] = 2;
            pBot.setBackground(shape(Color.parseColor("#245CCB"), 12, 0)); pBot.setTextColor(Color.WHITE);
            pDiag.setBackground(shape(surface, 12, line)); pDiag.setTextColor(ink);
            pTop.setBackground(shape(surface, 12, line)); pTop.setTextColor(ink);
            watermarkOverlay.setRotation(0f);
            watermarkOverlay.setTextSize(14);
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) watermarkOverlay.getLayoutParams();
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.setMargins(0, 0, 0, dp(14));
            watermarkOverlay.setLayoutParams(p);
        });

        customInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                stampText[0] = s.toString().trim();
                watermarkOverlay.setText(stampText[0]);
            }
            public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Apply Stamp to All Pages", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Stamp & Watermark PDF",
            "Preview watermark on pages",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable loadPage = () -> {
            pageIndicator.setText("Preview on: Page " + (targetPage[0] + 1) + " of " + totalPages);
            loadPdfPageThumbnail(sourceUri, targetPage[0], 400, bmp -> {
                if (bmp != null) pageImage.setImageBitmap(bmp);
            });
        };

        prevPage.setOnClickListener(v -> {
            if (targetPage[0] > 0) {
                targetPage[0]--;
                loadPage.run();
            }
        });
        nextPage.setOnClickListener(v -> {
            if (targetPage[0] < totalPages - 1) {
                targetPage[0]++;
                loadPage.run();
            }
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            String txt = customInput.getText().toString().trim();
            if (!txt.isEmpty()) {
                processPdf("stamp", sources, stampPos[0], txt);
            }
        });

        loadPage.run();
        dialog.show();
    }

    private void showVisualAnnotateDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        final int[] targetPage = {0};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setContentDescription("Previous page");
        prevPage.setBackground(shape(bg, 10, 0));
        prevPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(prevPage, lp(44, 44));

        TextView pageIndicator = text("Preview: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setContentDescription("Next page");
        nextPage.setBackground(shape(bg, 10, 0));
        nextPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(nextPage, lp(44, 44));

        content.addView(pagerRow, lp(-1, 44));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, 0));
        LinearLayout.LayoutParams fp = lp(-1, 240);
        fp.topMargin = dp(10);
        content.addView(previewFrame, fp);

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        TextView footerOverlay = text("Page 1", 12, true, Color.DKGRAY);
        FrameLayout.LayoutParams fop = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.START);
        fop.setMargins(dp(14), 0, 0, dp(10));
        previewFrame.addView(footerOverlay, fop);

        TextView label = text("Footer text prefix:", 13, true, ink);
        LinearLayout.LayoutParams lp = lp(-1, -2);
        lp.topMargin = dp(12);
        content.addView(label, lp);

        EditText footerInput = new EditText(this);
        footerInput.setText("Page");
        footerInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleEditText(footerInput);
        content.addView(footerInput, lp(-1, 46));

        footerInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                footerOverlay.setText(s.toString().trim() + "  " + (targetPage[0] + 1));
            }
            public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Apply Page Numbers to All Pages", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Add Watermark & Page Numbers",
            "Visually preview page footer and numbering",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable loadPage = () -> {
            pageIndicator.setText("Preview: Page " + (targetPage[0] + 1) + " of " + totalPages);
            footerOverlay.setText(footerInput.getText().toString().trim() + "  " + (targetPage[0] + 1));
            loadPdfPageThumbnail(sourceUri, targetPage[0], 400, bmp -> {
                if (bmp != null) pageImage.setImageBitmap(bmp);
            });
        };

        prevPage.setOnClickListener(v -> {
            if (targetPage[0] > 0) {
                targetPage[0]--;
                loadPage.run();
            }
        });
        nextPage.setOnClickListener(v -> {
            if (targetPage[0] < totalPages - 1) {
                targetPage[0]++;
                loadPage.run();
            }
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            String txt = footerInput.getText().toString().trim();
            if (!txt.isEmpty()) {
                processPdf("annotate", sources, 0, txt);
            }
        });

        loadPage.run();
        dialog.show();
    }

    private void showVisualFillFormDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        final int[] targetPage = {0};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setContentDescription("Previous page");
        prevPage.setBackground(shape(bg, 10, 0));
        prevPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(prevPage, lp(44, 44));

        TextView pageIndicator = text("Preview: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setContentDescription("Next page");
        nextPage.setBackground(shape(bg, 10, 0));
        nextPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(nextPage, lp(44, 44));

        content.addView(pagerRow, lp(-1, 44));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, 0));
        LinearLayout.LayoutParams fp = lp(-1, 180);
        fp.topMargin = dp(8);
        content.addView(previewFrame, fp);

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        TextView formFieldsHeader = text("Interactive Form Fields:", 13, true, ink);
        LinearLayout.LayoutParams flp = lp(-1, -2);
        flp.topMargin = dp(12);
        content.addView(formFieldsHeader, flp);

        LinearLayout fieldsContainer = column();
        content.addView(fieldsContainer, lp(-1, -2));

        CheckBox flattenCheck = new CheckBox(this);
        flattenCheck.setText("Flatten form (make read-only)");
        flattenCheck.setTextColor(ink);
        flattenCheck.setChecked(true);
        LinearLayout.LayoutParams fcp = lp(-1, -2);
        fcp.topMargin = dp(10);
        content.addView(flattenCheck, fcp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Save & Fill Form", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Fill PDF Form Fields",
            "Fill detected PDF form inputs",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        ArrayList<String> fieldKeys = new ArrayList<>();
        ArrayList<EditText> fieldInputs = new ArrayList<>();

        worker.execute(() -> {
            ArrayList<String> detected = PdfOperations.getFormFieldNames(getContentResolver(), sourceUri);
            runOnUiThread(() -> {
                fieldsContainer.removeAllViews();
                if (detected.isEmpty()) {
                    TextView hint = text("No standard form fields detected. Enter key=value pairs manually:", 12, false, muted);
                    fieldsContainer.addView(hint);
                    EditText manualIn = new EditText(MainActivity.this);
                    manualIn.setHint("Name=John Doe\nDate=2026-10-03");
                    manualIn.setMinLines(3);
                    manualIn.setPadding(dp(12), dp(8), dp(12), dp(8));
                    styleEditText(manualIn);
                    fieldsContainer.addView(manualIn, lp(-1, -2));
                    fieldKeys.add("MANUAL");
                    fieldInputs.add(manualIn);
                } else {
                    for (String key : detected) {
                        TextView lbl = text(key, 12, true, accent);
                        LinearLayout.LayoutParams lp1 = lp(-1, -2);
                        lp1.topMargin = dp(6);
                        fieldsContainer.addView(lbl, lp1);
                        EditText valIn = new EditText(MainActivity.this);
                        valIn.setHint("Value for " + key);
                        valIn.setPadding(dp(12), dp(8), dp(12), dp(8));
                        styleEditText(valIn);
                        fieldsContainer.addView(valIn, lp(-1, 46));
                        fieldKeys.add(key);
                        fieldInputs.add(valIn);
                    }
                }
            });
        });

        Runnable loadPage = () -> {
            pageIndicator.setText("Preview: Page " + (targetPage[0] + 1) + " of " + totalPages);
            loadPdfPageThumbnail(sourceUri, targetPage[0], 400, bmp -> {
                if (bmp != null) pageImage.setImageBitmap(bmp);
            });
        };

        prevPage.setOnClickListener(v -> {
            if (targetPage[0] > 0) {
                targetPage[0]--;
                loadPage.run();
            }
        });
        nextPage.setOnClickListener(v -> {
            if (targetPage[0] < totalPages - 1) {
                targetPage[0]++;
                loadPage.run();
            }
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            StringBuilder sb = new StringBuilder();
            if (fieldKeys.size() == 1 && "MANUAL".equals(fieldKeys.get(0))) {
                sb.append(fieldInputs.get(0).getText().toString().trim());
            } else {
                for (int i = 0; i < fieldKeys.size(); i++) {
                    String val = fieldInputs.get(i).getText().toString().trim();
                    if (!val.isEmpty()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(fieldKeys.get(i)).append("=").append(val);
                    }
                }
            }
            processPdf("fillForm", sources, flattenCheck.isChecked() ? 1 : 0, sb.toString());
        });

        loadPage.run();
        dialog.show();
    }

    private void showVisualSignDialog(ArrayList<Uri> sources, int totalPages) {
        Uri sourceUri = sources.get(0);
        final int[] targetPage = {totalPages - 1};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setContentDescription("Previous page");
        prevPage.setBackground(shape(bg, 10, 0));
        prevPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(prevPage, lp(44, 44));

        TextView pageIndicator = text("Sign on: Page " + (targetPage[0] + 1) + " of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setContentDescription("Next page");
        nextPage.setBackground(shape(bg, 10, 0));
        nextPage.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        pagerRow.addView(nextPage, lp(44, 44));

        content.addView(pagerRow, lp(-1, 44));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, 0));
        LinearLayout.LayoutParams fp = lp(-1, 240);
        fp.topMargin = dp(10);
        content.addView(previewFrame, fp);

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        LinearLayout sigBox = column();
        pad(sigBox, 6, 6, 6, 6);
        sigBox.setBackground(shape(isDark() ? Color.argb(200, 30, 40, 50) : Color.argb(200, 240, 245, 255), 8, accent));
        TextView sigNameTxt = text("Digitally Signed by:", 9, true, accent);
        sigBox.addView(sigNameTxt);
        TextView sigUserTxt = text("Verified Signer", 11, true, ink);
        sigBox.addView(sigUserTxt);
        TextView sigDateTxt = text("Date: " + DateFormat.getDateInstance().format(new Date()), 9, false, muted);
        sigBox.addView(sigDateTxt);

        FrameLayout.LayoutParams sbp = new FrameLayout.LayoutParams(dp(130), -2, Gravity.BOTTOM | Gravity.END);
        sbp.setMargins(0, 0, dp(14), dp(14));
        previewFrame.addView(sigBox, sbp);

        TextView label = text("Signer name:", 13, true, ink);
        LinearLayout.LayoutParams lp = lp(-1, -2);
        lp.topMargin = dp(12);
        content.addView(label, lp);

        EditText signerInput = new EditText(this);
        signerInput.setHint("e.g. John Doe");
        signerInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        styleEditText(signerInput);
        content.addView(signerInput, lp(-1, 46));

        signerInput.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String n = s.toString().trim();
                sigUserTxt.setText(n.isEmpty() ? "Verified Signer" : n);
            }
            public void afterTextChanged(android.text.Editable e) {}
        });

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Sign Page " + (targetPage[0] + 1), true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Digital Signature Stamp",
            "Place signature stamp visually on PDF",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable loadPage = () -> {
            pageIndicator.setText("Sign on: Page " + (targetPage[0] + 1) + " of " + totalPages);
            confirmBtn.setText("Sign Page " + (targetPage[0] + 1));
            loadPdfPageThumbnail(sourceUri, targetPage[0], 400, bmp -> {
                if (bmp != null) pageImage.setImageBitmap(bmp);
            });
        };

        prevPage.setOnClickListener(v -> {
            if (targetPage[0] > 0) {
                targetPage[0]--;
                loadPage.run();
            }
        });
        nextPage.setOnClickListener(v -> {
            if (targetPage[0] < totalPages - 1) {
                targetPage[0]++;
                loadPage.run();
            }
        });

        confirmBtn.setOnClickListener(v -> {
            dialog.dismiss();
            String name = signerInput.getText().toString().trim();
            processPdf("sign", sources, targetPage[0] + 1, name.isEmpty() ? "Verified User" : name);
        });

        loadPage.run();
        dialog.show();
    }

    private void requestPdfImagePage(Uri image) {
        ArrayList<Uri> sources = pendingPdfImageSources != null ? new ArrayList<>(pendingPdfImageSources) : null;
        pendingPdfImageSources = null;
        if (sources == null || sources.isEmpty() || image == null) {
            toast("No PDF or image selected");
            return;
        }
        toast("Preparing PDF and generating preview...");
        worker.execute(() -> {
            try {
                int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                Bitmap origBmp = null;
                try (InputStream in = getContentResolver().openInputStream(image)) {
                    origBmp = BitmapFactory.decodeStream(in);
                }
                if (origBmp == null) {
                    throw new IOException("Could not decode the selected image");
                }
                final Bitmap decodedBmp = origBmp;
                runOnUiThread(() -> showVisualAddImageDialog(sources, count, image, decodedBmp));
            } catch (Exception e) {
                String errMsg = getHumanReadableErrorMessage(e, "Could not prepare PDF image insert");
                runOnUiThread(() -> toast(errMsg));
            }
        });
    }

    private void requestPdfOptionsLegacy(String operation, ArrayList<Uri> sources) {
        if (operation.equals("metadata")) {
            worker.execute(() -> {
                try {
                    String details = PdfOperations.metadata(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> dialogBuilder().setTitle(displayName(sources.get(0))).setMessage(details).setPositiveButton("Done", null).show());
                } catch (Exception e) {
                    String errMsg = getHumanReadableErrorMessage(e, "Could not read PDF details");
                    runOnUiThread(() -> toast(errMsg));
                }
            });
            return;
        }
        if (operation.equals("pdfa") || operation.equals("repairPdf")) {
            processPdf(operation, sources, 0, null);
            return;
        }
        if (operation.equals("stamp")) {
            String[] presets = {"CONFIDENTIAL (Diagonal Watermark)", "APPROVED (Diagonal Watermark)", "DRAFT (Diagonal Watermark)", "CONFIDENTIAL (Top Banner)", "APPROVED (Top Banner)", "DRAFT (Top Banner)", "Custom Text Banner..."};
            dialogBuilder().setTitle("Stamp PDF").setItems(presets, (d, w) -> {
                if (w < 6) {
                    String text = w % 3 == 0 ? "CONFIDENTIAL" : w % 3 == 1 ? "APPROVED" : "DRAFT";
                    int pos = w < 3 ? 0 : 1;
                    processPdf(operation, sources, pos, text);
                } else {
                    EditText textInput = new EditText(this);
                    textInput.setHint("e.g. OFFICIAL COPY");
                    textInput.setPadding(dp(14), dp(10), dp(14), dp(10));
                    styleEditText(textInput);
                    dialogBuilder().setTitle("Custom Stamp").setView(textInput).setPositiveButton("Stamp", (cd, cw) -> {
                        String txt = textInput.getText().toString().trim();
                        if (!txt.isEmpty()) processPdf(operation, sources, 0, txt);
                    }).setNegativeButton("Cancel", null).show();
                }
            }).show();
            return;
        }
        if (operation.equals("fillForm")) {
            LinearLayout form = column();
            TextView guide = text("Enter field values (FieldName=Value, one per line):", 13, false, muted);
            form.addView(guide);
            EditText inputs = new EditText(this);
            inputs.setHint("Name=John Doe\nDate=2026-10-03");
            inputs.setMinLines(4);
            inputs.setPadding(dp(14), dp(10), dp(14), dp(10));
            styleEditText(inputs);
            form.addView(inputs);
            CheckBox flatten = new CheckBox(this);
            flatten.setText("Flatten form (make read-only)");
            flatten.setTextColor(ink);
            flatten.setChecked(true);
            form.addView(flatten);
            dialogBuilder().setTitle("Fill PDF Form").setView(form).setPositiveButton("Fill Form", (d, w) -> {
                String values = inputs.getText().toString().trim();
                processPdf(operation, sources, flatten.isChecked() ? 1 : 0, values);
            }).setNegativeButton("Cancel", null).show();
            return;
        }
        if (operation.equals("sign")) {
            LinearLayout form = column();
            EditText signer = new EditText(this);
            signer.setHint("Signer name or stamp (e.g. John Doe, ✓ Verified, ★ Approved)");
            signer.setPadding(dp(14), dp(10), dp(14), dp(10));
            styleEditText(signer);
            form.addView(signer);
            EditText pageNum = new EditText(this);
            pageNum.setHint("Page number (blank for last page)");
            pageNum.setInputType(2);
            pageNum.setPadding(dp(14), dp(10), dp(14), dp(10));
            styleEditText(pageNum);
            form.addView(pageNum);
            dialogBuilder().setTitle("Digital Signature Stamp").setMessage("Add verified visual signature stamp to PDF.").setView(form).setPositiveButton("Sign PDF", (d, w) -> {
                String name = signer.getText().toString().trim();
                int pg = 0;
                try {
                    pg = Integer.parseInt(pageNum.getText().toString().trim());
                } catch (Exception ignored) {}
                processPdf(operation, sources, pg, name.isEmpty() ? "Verified Signer" : name);
            }).setNegativeButton("Cancel", null).show();
            return;
        }
        if (operation.equals("editPdfMeta")) {
            LinearLayout form = column();
            EditText titleIn = new EditText(this); titleIn.setHint("Title"); styleEditText(titleIn); titleIn.setPadding(dp(14), dp(8), dp(14), dp(8)); form.addView(titleIn);
            EditText authIn = new EditText(this); authIn.setHint("Author"); styleEditText(authIn); authIn.setPadding(dp(14), dp(8), dp(14), dp(8)); form.addView(authIn);
            EditText subjIn = new EditText(this); subjIn.setHint("Subject"); styleEditText(subjIn); subjIn.setPadding(dp(14), dp(8), dp(14), dp(8)); form.addView(subjIn);
            EditText keywIn = new EditText(this); keywIn.setHint("Keywords"); styleEditText(keywIn); keywIn.setPadding(dp(14), dp(8), dp(14), dp(8)); form.addView(keywIn);
            dialogBuilder().setTitle("Edit PDF Metadata").setView(form).setPositiveButton("Save", (d, w) -> {
                String metaData = titleIn.getText().toString().trim() + "\n" + authIn.getText().toString().trim() + "\n" + subjIn.getText().toString().trim() + "\n" + keywIn.getText().toString().trim();
                processPdf(operation, sources, 0, metaData);
            }).setNegativeButton("Cancel", null).show();
            return;
        }
        if (operation.equals("rotate")) {
            dialogBuilder().setTitle("Rotate pages").setItems(new String[]{"90° clockwise", "180°", "270° clockwise"}, (d, w) -> processPdf(operation, sources, w == 0 ? 90 : w == 1 ? 180 : 270, null)).show();
            return;
        }
        if (operation.startsWith("compress")) {
            LinearLayout container = column();
            pad(container, 20, 16, 20, 16);

            int defaultLevel = getPreferences(0).getInt("default_pdf_compression_level", 70);
            if (defaultLevel <= 2) {
                defaultLevel = defaultLevel == 0 ? 30 : defaultLevel == 1 ? 70 : 90;
            }

            TextView label = new TextView(this);
            label.setText("Reduce size: " + defaultLevel + "%");
            label.setTextColor(ink);
            label.setTextSize(16);
            label.setTypeface(null, Typeface.BOLD);
            container.addView(label);

            TextView subtitle = new TextView(this);
            subtitle.setText("Higher percentage compresses images and optimizes PDF resources more aggressively while preserving text content, layout, and font readability.");
            subtitle.setTextColor(muted);
            subtitle.setTextSize(12);
            container.addView(subtitle, layoutTop(6));

            SeekBar slider = new SeekBar(this);
            slider.setMax(99);
            slider.setProgress(Math.max(0, Math.min(99, defaultLevel - 1)));
            slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                    int val = p + 1;
                    label.setText("Reduce size: " + val + "%");
                }
                @Override public void onStartTrackingTouch(SeekBar sb) {}
                @Override public void onStopTrackingTouch(SeekBar sb) {}
            });
            container.addView(slider, layoutTop(16));

            LinearLayout rangeLabels = new LinearLayout(this);
            rangeLabels.setOrientation(LinearLayout.HORIZONTAL);
            TextView minTv = new TextView(this);
            minTv.setText("1% (Light)");
            minTv.setTextColor(muted);
            minTv.setTextSize(11);
            rangeLabels.addView(minTv, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView midTv = new TextView(this);
            midTv.setText("50% (Balanced)");
            midTv.setTextColor(muted);
            midTv.setTextSize(11);
            midTv.setGravity(Gravity.CENTER);
            rangeLabels.addView(midTv, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView maxTv = new TextView(this);
            maxTv.setText("100% (Maximum)");
            maxTv.setTextColor(muted);
            maxTv.setTextSize(11);
            maxTv.setGravity(Gravity.END);
            rangeLabels.addView(maxTv, new LinearLayout.LayoutParams(0, -2, 1f));
            container.addView(rangeLabels, layoutTop(4));

            dialogBuilder()
                    .setTitle("Compress PDF")
                    .setView(container)
                    .setPositiveButton("Compress", (d, w) -> {
                        int compressionPercent = slider.getProgress() + 1;
                        processPdf(operation, sources, compressionPercent, null);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        if (operation.equals("protect")) {
            LinearLayout form = column();
            EditText password = new EditText(this);
            password.setHint("Open password");
            password.setInputType(129);
            password.setPadding(dp(14), dp(10), dp(14), dp(10));
            styleEditText(password);
            form.addView(password);
            CheckBox print = new CheckBox(this);
            print.setText("Allow printing");
            print.setTextColor(ink);
            print.setChecked(true);
            form.addView(print);
            CheckBox copy = new CheckBox(this);
            copy.setText("Allow copying text");
            copy.setTextColor(ink);
            copy.setChecked(true);
            form.addView(copy);
            CheckBox edit = new CheckBox(this);
            edit.setText("Allow editing and annotations");
            edit.setTextColor(ink);
            edit.setChecked(true);
            form.addView(edit);
            dialogBuilder().setTitle("Protect PDF").setMessage("Choose what other PDF apps may do.").setView(form).setPositiveButton("Protect", (d, w) -> {
                int allowed = (print.isChecked() ? 1 : 0) | (copy.isChecked() ? 2 : 0) | (edit.isChecked() ? 4 : 0);
                processPdf(operation, sources, allowed, password.getText().toString());
            }).setNegativeButton("Cancel", null).show();
            return;
        }
        if (operation.equals("crop") || operation.equals("annotate") || operation.equals("unlock")) {
            EditText input = new EditText(this);
            input.setHint(operation.equals("crop") ? "12" : operation.equals("unlock") ? "Password" : "Text");
            if (operation.equals("unlock")) input.setInputType(129);
            input.setPadding(dp(14), dp(10), dp(14), dp(10));
            styleEditText(input);
            dialogBuilder().setTitle(operationLabel(operation)).setMessage(operation.equals("annotate") ? "Text to place in each page footer" : operation.equals("unlock") ? "Password" : "Enter crop margin in points (e.g. 12)").setView(input).setPositiveButton("Continue", (d, w) -> processPdf(operation, sources, 0, input.getText().toString())).setNegativeButton("Cancel", null).show();
            return;
        }
        processPdf(operation, sources, 0, null);
    }

    private String operationLabel(String op) {
        switch (op) {
            case "merge":
                return "Merge PDFs";
            case "split":
                return "Split into pages";
            case "compress":
                return "Compress PDF";
            case "extract":
                return "Extract pages";
            case "delete":
                return "Delete pages";
            case "reorder":
                return "Reorder pages";
            case "crop":
                return "Crop margins";
            case "annotate":
                return "Add watermark and page numbers";
            case "stamp":
                return "Stamp PDF";
            case "pdfa":
                return "Convert to PDF/A";
            case "repairPdf":
                return "Repair PDF";
            case "fillForm":
                return "Fill PDF Form";
            case "sign":
                return "Digital Signature Stamp";
            case "editPdfMeta":
                return "Edit PDF Metadata";
            case "addImage":
                return "Insert image";
            case "resize":
                return "Resize pages";
            case "protect":
                return "Protect PDF";
            case "unlock":
                return "Remove PDF password";
            default:
                return op;
        }
    }

    private void openExifEditorPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        try {
            startActivityForResult(i, PICK_EXIF_IMAGE);
        } catch (ActivityNotFoundException e) {
            toast("No image picker is available");
        }
    }

    private void showExifEditDialog(Uri uri) {
        LinearLayout form = column();
        pad(form, 20, 16, 20, 16);
        EditText desc = new EditText(this); desc.setHint("Image Description"); styleEditText(desc); form.addView(desc, lp(-1, 48));
        EditText artist = new EditText(this); artist.setHint("Artist / Author"); styleEditText(artist); form.addView(artist, layoutTop(8));
        EditText copyright = new EditText(this); copyright.setHint("Copyright"); styleEditText(copyright); form.addView(copyright, layoutTop(8));
        EditText make = new EditText(this); make.setHint("Camera Make"); styleEditText(make); form.addView(make, layoutTop(8));
        EditText model = new EditText(this); model.setHint("Camera Model"); styleEditText(model); form.addView(model, layoutTop(8));
        CheckBox scrubGps = new CheckBox(this);
        scrubGps.setText("Scrub GPS Location Data");
        scrubGps.setTextColor(ink);
        form.addView(scrubGps, layoutTop(8));

        dialogBuilder()
                .setTitle("Edit EXIF Metadata")
                .setView(form)
                .setPositiveButton("Save", (d, w) -> {
                    if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
                        toast("Insufficient storage space on device");
                        return;
                    }
                    toast("Updating EXIF metadata...");
                    worker.execute(() -> {
                        try {
                            File dir = new File(getCacheDir(), "fileswitch");
                            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
                            String origName = displayName(uri);
                            String ext = origName.contains(".") ? origName.substring(origName.lastIndexOf('.')) : ".jpg";
                            File dest = new File(dir, UUID.randomUUID() + "-" + safeName(origName));
                            try (InputStream in = getContentResolver().openInputStream(uri); FileOutputStream out = new FileOutputStream(dest)) {
                                byte[] buf = new byte[32768];
                                int n;
                                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                            }
                            ImageTools.editExif(dest, desc.getText().toString().trim(), artist.getText().toString().trim(), copyright.getText().toString().trim(), make.getText().toString().trim(), model.getText().toString().trim(), scrubGps.isChecked());
                            OutputFile output = saveOutputFileToFileSwitchDir(new OutputFile(dest, stripUuidPrefix(dest.getName()), "image/jpeg", "jpg"));
                            if (output == null || output.file == null || !output.file.exists() || output.file.length() <= 0) {
                                throw new IOException("Could not save updated image to FileSwitch folder");
                            }
                            saveHistory(output.name, "Edit EXIF", "jpg", output.file.length(), "complete", output.file.getAbsolutePath(), "image/jpeg");
                            runOnUiThread(() -> {
                                clearInputFiles();
                                InputFile item = new InputFile(outputUri(output), output.name, "jpg");
                                item.output = output;
                                item.state = "Ready";
                                files.add(item);
                                showPage("Convert");
                                progressText.setText("EXIF metadata updated");
                            });
                        } catch (Exception e) {
                            String errMsg = getHumanReadableErrorMessage(e, "Failed to update EXIF");
                            saveHistory(displayName(uri), "Edit EXIF", "jpg", 0, "failed", null, null);
                            runOnUiThread(() -> {
                                toast(errMsg);
                                progressText.setText("Failed: " + errMsg);
                            });
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void openContactSheetPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, PICK_CONTACT_SHEET);
        } catch (ActivityNotFoundException e) {
            toast("No image picker is available");
        }
    }

    private void showContactSheetDialog(ArrayList<Uri> images) {
        String[] gridOptions = {"2 Columns (2 × N)", "3 Columns (3 × N)", "4 Columns (4 × N)"};
        dialogBuilder()
                .setTitle("Contact Sheet Grid")
                .setItems(gridOptions, (d, w) -> {
                    if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
                        toast("Insufficient storage space on device");
                        return;
                    }
                    int cols = w == 0 ? 2 : w == 1 ? 3 : 4;
                    int rows = (int) Math.ceil((double) images.size() / cols);
                    toast("Generating contact sheet...");
                    worker.execute(() -> {
                        try {
                            File dir = new File(getCacheDir(), "fileswitch");
                            File sheet = ImageTools.generateContactSheet(getContentResolver(), images, cols, rows, dir);
                            OutputFile out = saveOutputFileToFileSwitchDir(new OutputFile(sheet, stripUuidPrefix(sheet.getName()), "image/png", "png"));
                            if (out == null || out.file == null || !out.file.exists() || out.file.length() <= 0) {
                                throw new IOException("Could not save contact sheet to FileSwitch folder");
                            }
                            saveHistory(out.name, "Contact Sheet", "png", out.file.length(), "complete", out.file.getAbsolutePath(), "image/png");
                            runOnUiThread(() -> {
                                clearInputFiles();
                                InputFile item = new InputFile(outputUri(out), out.name, "png");
                                item.output = out;
                                item.state = "Ready";
                                files.add(item);
                                showPage("Convert");
                                progressText.setText("Contact sheet created");
                            });
                        } catch (Exception e) {
                            String errMsg = getHumanReadableErrorMessage(e, "Failed to create contact sheet");
                            saveHistory("Contact Sheet", "Contact Sheet", "png", 0, "failed", null, null);
                            runOnUiThread(() -> {
                                toast(errMsg);
                                progressText.setText("Failed: " + errMsg);
                            });
                        }
                    });
                })
                .show();
    }

    private void openRepairDocPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, PICK_REPAIR_DOC);
        } catch (ActivityNotFoundException e) {
            toast("No file picker is available");
        }
    }

    private void repairDocument(Uri uri) {
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            return;
        }
        toast("Preparing document and repairing text...");
        worker.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "fileswitch");
                String name = displayName(uri);
                String type = detectType(uri, name);
                OfficeTools.Result res = OfficeTools.repairDocument(getContentResolver(), uri, name, type, dir);
                OutputFile out = saveOutputFileToFileSwitchDir(new OutputFile(res.file, res.name, res.mime, "docx"));
                if (out == null || out.file == null || !out.file.exists() || out.file.length() <= 0) {
                    throw new IOException("Could not save repaired document to FileSwitch folder");
                }
                saveHistory(out.name, "Repair Document", "docx", out.file.length(), "complete", out.file.getAbsolutePath(), out.mime);
                runOnUiThread(() -> {
                    clearInputFiles();
                    InputFile item = new InputFile(outputUri(out), out.name, "docx");
                    item.output = out;
                    item.state = "Ready";
                    files.add(item);
                    showPage("Convert");
                    progressText.setText("Document text salvaged & repaired");
                });
            } catch (Exception e) {
                String errMsg = getHumanReadableErrorMessage(e, "Document could not be repaired");
                saveHistory(displayName(uri), "Repair Document", "docx", 0, "failed", null, null);
                runOnUiThread(() -> {
                    toast(errMsg);
                    progressText.setText("Failed: " + errMsg);
                });
            }
        });
    }

    private void openZipPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/x-tar", "application/gzip", "application/x-gzip", "application/x-compressed-tar"});
        try {
            startActivityForResult(i, PICK_ZIP);
        } catch (ActivityNotFoundException e) {
            toast("No archive picker is available");
        }
    }

    private void openCreateZipPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, PICK_CREATE_ZIP);
        } catch (ActivityNotFoundException e) {
            toast("No file picker is available");
        }
    }

    private void promptCreateZip(ArrayList<Uri> picked) {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setHint("File name (optional)");
        nameInput.setText("FileSwitch-files.zip");
        nameInput.selectAll();
        styleEditText(nameInput);
        nameInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        dialogBuilder()
                .setTitle("Create ZIP")
                .setView(nameInput)
                .setPositiveButton("Create", (d, w) -> {
                    String custom = nameInput.getText().toString().trim();
                    createZipFromUris(picked, custom);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void createZipFromUris(ArrayList<Uri> sources, String customName) {
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            return;
        }
        toast("Creating archive from " + sources.size() + (sources.size() == 1 ? " file..." : " files..."));
        worker.execute(() -> {
            File zip = null;
            final String outName = (customName == null || customName.trim().isEmpty()) ? "FileSwitch-files.zip" : ensureExtension(customName, "zip");
            try {
                File dir = new File(getCacheDir(), "fileswitch");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare ZIP");
                zip = new File(dir, UUID.randomUUID() + "-" + safeName(outName));
                HashSet<String> names = new HashSet<>();
                long total = 0;
                try (ZipOutputStream out = new ZipOutputStream(new FileOutputStream(zip))) {
                    for (Uri uri : sources) {
                        String base = safeName(displayName(uri));
                        if (base.isEmpty()) base = "file";
                        String name = base;
                        int suffix = 2;
                        while (!names.add(name)) {
                            int dot = base.lastIndexOf('.');
                            name = dot > 0 ? base.substring(0, dot) + " (" + suffix++ + ")" + base.substring(dot) : base + " (" + suffix++ + ")";
                        }
                        out.putNextEntry(new ZipEntry(name));
                        try (InputStream in = getContentResolver().openInputStream(uri)) {
                            if (in == null) throw new IOException("A selected file could not be opened");
                            byte[] buffer = new byte[32768];
                            int n;
                            while ((n = in.read(buffer)) > 0) {
                                total += n;
                                if (total > 500L * 1024 * 1024)
                                    throw new IOException("Files exceed the 500 MB ZIP limit");
                                out.write(buffer, 0, n);
                            }
                        }
                        out.closeEntry();
                    }
                }
                File archive = zip;
                OutputFile result = saveOutputFileToFileSwitchDir(new OutputFile(archive, outName, "application/zip", "zip"));
                if (result == null || result.file == null || !result.file.exists() || result.file.length() <= 0) {
                    throw new IOException("Could not save ZIP archive to FileSwitch folder");
                }
                saveHistory(result.name, "ZIP Archive", "zip", result.file.length(), "complete", result.file.getAbsolutePath(), "application/zip");
                runOnUiThread(() -> {
                    clearInputFiles();
                    InputFile item = new InputFile(outputUri(result), result.name, "zip");
                    item.output = result;
                    item.state = "Ready";
                    files.add(item);
                    batchArchive = null;
                    showPage("Convert");
                });
            } catch (Exception e) {
                if (zip != null && zip.exists()) zip.delete();
                String errMsg = getHumanReadableErrorMessage(e, "Could not create ZIP");
                saveHistory(outName, "ZIP Archive", "zip", 0, "failed", null, null);
                runOnUiThread(() -> {
                    toast(errMsg);
                    progressText.setText("Failed: " + errMsg);
                });
            }
        });
    }

    private void extractZip(Uri source) {
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            return;
        }
        toast("Preparing and extracting archive...");
        worker.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "fileswitch");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare files");
                ArrayList<ZipTools.Entry> extracted = ZipTools.extract(getContentResolver(), source, dir);
                if (extracted == null || extracted.isEmpty()) {
                    throw new IOException("This archive contains no readable files");
                }
                ArrayList<OutputFile> validOutputs = new ArrayList<>();
                for (ZipTools.Entry entry : extracted) {
                    OutputFile extractedFile = saveOutputFileToFileSwitchDir(new OutputFile(entry.file, entry.name, entry.mime, "extracted"));
                    if (extractedFile != null && extractedFile.file != null && extractedFile.file.exists() && extractedFile.file.length() > 0) {
                        validOutputs.add(extractedFile);
                    }
                }
                if (validOutputs.isEmpty()) {
                    throw new IOException("Failed to extract readable files from archive");
                }
                for (OutputFile out : validOutputs) {
                    saveHistory(out.name, "Extract ZIP", out.target, out.file.length(), "complete", out.file.getAbsolutePath(), out.mime);
                }
                runOnUiThread(() -> {
                    clearInputFiles();
                    for (OutputFile out : validOutputs) {
                        Uri fileUri = outputUri(out);
                        InputFile f = new InputFile(fileUri, out.name, detectType(fileUri));
                        f.output = out;
                        f.state = "Extracted";
                        files.add(f);
                    }
                    showPage("Convert");
                    progressText.setText("Extracted " + validOutputs.size() + (validOutputs.size() == 1 ? " file" : " files"));
                });
            } catch (Exception e) {
                String errMsg = getHumanReadableErrorMessage(e, "Could not extract this archive");
                saveHistory(displayName(source), "Extract ZIP", "extracted", 0, "failed", null, null);
                runOnUiThread(() -> {
                    toast(errMsg);
                    progressText.setText("Failed: " + errMsg);
                });
            }
        });
    }

    private void processPdf(String operation, ArrayList<Uri> sources, int option, String value) {
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            return;
        }
        String opLabel = operationLabel(operation);
        runOnUiThread(() -> toast("Processing " + opLabel.toLowerCase(Locale.ROOT) + "..."));
        worker.execute(() -> {
            ArrayList<File> outputs = new ArrayList<>();
            File dir = new File(getCacheDir(), "fileswitch");
            try {
                if (operation.equals("merge")) {
                    if (sources.size() < 2) throw new IOException("Select at least two PDF files");
                    outputs.add(PdfOperations.merge(getContentResolver(), sources, dir));
                } else {
                    Uri source = sources.get(0);
                    switch (operation) {
                        case "split":
                            outputs.addAll(PdfOperations.split(getContentResolver(), source, dir));
                            break;
                        case "extract":
                            outputs.add(PdfOperations.extract(getContentResolver(), source, parsePages(value, PdfOperations.pageCount(getContentResolver(), source)), dir));
                            break;
                        case "delete":
                            outputs.add(PdfOperations.deletePages(getContentResolver(), source, parsePages(value, PdfOperations.pageCount(getContentResolver(), source)), dir));
                            break;
                        case "reorder":
                            outputs.add(PdfOperations.reorder(getContentResolver(), source, parsePages(value, PdfOperations.pageCount(getContentResolver(), source)), dir));
                            break;
                        case "rotate":
                            outputs.add(PdfOperations.rotate(getContentResolver(), source, option, dir));
                            break;
                        case "resize":
                            if (value != null && !value.trim().isEmpty()) {
                                int pct = Integer.parseInt(value.replace("percent:", "").trim());
                                outputs.add(PdfOperations.resizePagesByPercent(getContentResolver(), source, pct, 150, dir));
                            } else {
                                outputs.add(PdfOperations.resizePages(getContentResolver(), source, option, 150, dir));
                            }
                            break;
                        case "addImage":
                            Uri imageUri = sources.get(1);
                            String imageType = detectType(imageUri, displayName(imageUri));
                            BitmapFactory.Options imageBounds = new BitmapFactory.Options();
                            imageBounds.inJustDecodeBounds = true;
                            try (InputStream imageStream = getContentResolver().openInputStream(imageUri)) {
                                BitmapFactory.decodeStream(imageStream, null, imageBounds);
                            }
                            if ((long) imageBounds.outWidth * imageBounds.outHeight > MAX_PIXELS)
                                throw new IOException("Image is too large for this phone");
                            Bitmap inserted = ImageTools.decode(getContentResolver(), imageUri, imageType);
                            try {
                                if (value != null && value.contains(":")) {
                                    String[] parts = value.split(":");
                                    int pageNum = Integer.parseInt(parts[0]);
                                    float nx = Float.parseFloat(parts[1]);
                                    float ny = Float.parseFloat(parts[2]);
                                    float nw = Float.parseFloat(parts[3]);
                                    float nh = Float.parseFloat(parts[4]);
                                    outputs.add(PdfOperations.insertImage(getContentResolver(), source, inserted, pageNum, nx, ny, nw, nh, dir));
                                } else {
                                    outputs.add(PdfOperations.insertImage(getContentResolver(), source, inserted, Integer.parseInt(value), dir));
                                }
                            } finally {
                                inserted.recycle();
                            }
                            break;
                        case "compress":
                            int compressionLevel = option > 0 ? option : 70;
                            outputs.add(PdfOperations.compress(getContentResolver(), source, compressionLevel, dir));
                            break;
                        case "crop":
                            if (value != null && (value.contains(":") || value.contains(","))) {
                                String[] parts = value.contains(":") ? value.split(":") : value.split(",");
                                float l = Float.parseFloat(parts[0].trim());
                                float t = Float.parseFloat(parts[1].trim());
                                float r = Float.parseFloat(parts[2].trim());
                                float b = Float.parseFloat(parts[3].trim());
                                outputs.add(PdfOperations.crop(getContentResolver(), source, l, t, r, b, dir));
                            } else {
                                float margin = Float.parseFloat(value.trim());
                                if (margin < 0) throw new IOException("Margin must be zero or more");
                                outputs.add(PdfOperations.crop(getContentResolver(), source, margin, dir));
                            }
                            break;
                        case "annotate":
                            if (value.trim().isEmpty()) throw new IOException("Enter the footer text");
                            outputs.add(PdfOperations.annotate(getContentResolver(), source, value.trim(), dir));
                            break;
                        case "stamp":
                            outputs.add(PdfOperations.stamp(getContentResolver(), source, value, option, dir));
                            break;
                        case "pdfa":
                            outputs.add(PdfOperations.convertToPdfA(getContentResolver(), source, dir));
                            break;
                        case "repairPdf":
                            outputs.add(PdfOperations.repair(getContentResolver(), source, dir));
                            break;
                        case "fillForm":
                            outputs.add(PdfOperations.fillForm(getContentResolver(), source, value, option != 0, dir));
                            break;
                        case "sign":
                            outputs.add(PdfOperations.addSignature(getContentResolver(), source, value, option, dir));
                            break;
                        case "editPdfMeta":
                            String[] metaParts = (value == null ? "" : value).split("\n", -1);
                            String title = metaParts.length > 0 ? metaParts[0] : "";
                            String author = metaParts.length > 1 ? metaParts[1] : "";
                            String subject = metaParts.length > 2 ? metaParts[2] : "";
                            String keywords = metaParts.length > 3 ? metaParts[3] : "";
                            outputs.add(PdfOperations.editMetadata(getContentResolver(), source, title, author, subject, keywords, dir));
                            break;
                        case "protect":
                            if (value.length() < 4) throw new IOException("Use a password with at least 4 characters");
                            outputs.add(PdfOperations.protect(getContentResolver(), source, value, option, dir));
                            break;
                        case "unlock":
                            outputs.add(PdfOperations.removePassword(getContentResolver(), source, value, dir));
                            break;
                        default:
                            throw new IOException("This PDF tool is not available");
                    }
                }
                if (outputs.isEmpty()) throw new IOException("No output was created");
                ArrayList<OutputFile> savedOutputs = new ArrayList<>();
                for (File f : outputs) {
                    if (f == null || !f.exists() || f.length() <= 0) {
                        throw new IOException("An output PDF page could not be created or is empty");
                    }
                    OutputFile out = saveOutputFileToFileSwitchDir(new OutputFile(f, stripUuidPrefix(f.getName()), "application/pdf", "pdf"));
                    if (out == null || out.file == null || !out.file.exists() || out.file.length() <= 0) {
                        throw new IOException("Could not save PDF to FileSwitch folder");
                    }
                    savedOutputs.add(out);
                }
                for (OutputFile so : savedOutputs) {
                    saveHistory(so.name, operationLabel(operation), "pdf", so.file.length(), "complete", so.file.getAbsolutePath(), "application/pdf");
                }
                runOnUiThread(() -> {
                    clearInputFiles();
                    InputFile result = new InputFile(sources.get(0), displayName(sources.get(0)), "pdf");
                    for (int i = 1; i < savedOutputs.size(); i++)
                        result.extraOutputs.add(savedOutputs.get(i));
                    result.output = savedOutputs.get(0);
                    result.state = "Ready";
                    files.add(result);
                    batchArchive = null;
                    showPage("Convert");
                    progressText.setText(operationLabel(operation) + " complete");
                });
            } catch (Exception e) {
                for (File f : outputs) {
                    if (f != null && f.exists()) f.delete();
                }
                String errMsg = getHumanReadableErrorMessage(e, "PDF operation failed");
                saveHistory(!sources.isEmpty() ? displayName(sources.get(0)) : "PDF Document", operationLabel(operation), "pdf", 0, "failed", null, null);
                runOnUiThread(() -> {
                    toast(errMsg);
                    progressText.setText("Failed: " + errMsg);
                });
            }
        });
    }

    private ArrayList<Integer> parsePages(String range, int count) throws IOException {
        ArrayList<Integer> pages = new ArrayList<>();
        try {
            for (String part : range.split(",")) {
                String[] ends = part.trim().split("-");
                int first = Integer.parseInt(ends[0].trim()), last = ends.length > 1 ? Integer.parseInt(ends[1].trim()) : first;
                if (first < 1 || last < first || last > count) throw new NumberFormatException();
                for (int page = first; page <= last; page++) pages.add(page - 1);
            }
        } catch (Exception e) {
            throw new IOException("Enter valid page numbers between 1 and " + count);
        }
        return pages;
    }

    private LinearLayout.LayoutParams layoutTop(int margin) {
        LinearLayout.LayoutParams p = lp(-1, -2);
        p.topMargin = dp(margin);
        return p;
    }

    private void renderHistory() {
        heading("Recent files", "Your latest conversions on this device.");
        String raw = getPreferences(0).getString("history", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            if (arr.length() == 0) {
                LinearLayout emptyBox = column();
                pad(emptyBox, 24, 28, 24, 28);
                emptyBox.setBackground(shape(surface, 20, line));
                emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);

                TextView icon = text("🕒", 32, false, ink);
                icon.setGravity(Gravity.CENTER);
                emptyBox.addView(icon, lp(-2, -2));

                TextView title = text("No conversion history", 16, true, ink);
                title.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams tlp = lp(-2, -2);
                tlp.topMargin = dp(10);
                emptyBox.addView(title, tlp);

                TextView desc = text("Files you convert and tools you use will appear here for easy access.", 13, false, muted);
                desc.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams dlp = lp(-1, -2);
                dlp.topMargin = dp(6);
                emptyBox.addView(desc, dlp);

                TextView cta = button("Convert a file", true, () -> showPage("Convert"));
                LinearLayout.LayoutParams clp = lp(-1, dp(48));
                clp.topMargin = dp(18);
                emptyBox.addView(cta, clp);

                card(body, emptyBox);
                return;
            }
            DateFormat dateFormat = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
            for (int i = 0; i < arr.length(); i++) {
                final int index = i;
                org.json.JSONObject o = arr.getJSONObject(i);
                LinearLayout r = column();
                pad(r, 14, 14, 14, 14);
                r.setBackground(shape(surface, 16, line));

                LinearLayout topRow = row();
                String name = o.optString("name", "File");
                String status = o.optString("status", "complete");
                boolean isComplete = "complete".equalsIgnoreCase(status);

                TextView nameText = text(name, 15, true, ink);
                topRow.addView(nameText, new LinearLayout.LayoutParams(0, -2, 1));

                TextView statusBadge = text(isComplete ? "COMPLETED" : "FAILED", 10, true, isComplete ? accent : Color.parseColor("#E53935"));
                statusBadge.setGravity(Gravity.CENTER);
                statusBadge.setBackground(shape(bg, 10, 0));
                pad(statusBadge, 8, 3, 8, 3);
                topRow.addView(statusBadge);

                TextView remove = text("×", 20, false, muted);
                remove.setGravity(Gravity.CENTER);
                remove.setContentDescription("Delete " + name + " from history");
                remove.setBackground(shape(bg, 14, 0));
                remove.setOnTouchListener((v, event) -> {
                    if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                        v.animate().scaleX(.92f).scaleY(.92f).setDuration(80).start();
                    else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                        v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    return false;
                });
                remove.setOnClickListener(v -> confirmDeleteHistoryEntry(index, name));
                LinearLayout.LayoutParams rm = lp(44, 44);
                rm.leftMargin = dp(8);
                topRow.addView(remove, rm);
                r.addView(topRow, lp(-1, -2));

                String conversion = o.optString("source", "FILE") + " → " + o.optString("target", "FILE");
                long timestamp = o.optLong("date", System.currentTimeMillis());
                String info = conversion + " · " + dateFormat.format(new Date(timestamp));
                if (o.optLong("size") > 0) info += " · " + sizeLabel(o.optLong("size"));
                TextView infoText = text(info, 12, false, muted);
                LinearLayout.LayoutParams ip = lp(-1, -2);
                ip.topMargin = dp(4);
                r.addView(infoText, ip);

                final String filePath = o.optString("filePath", null);
                final String mime = o.optString("mime", null);
                boolean fileExists = filePath != null && new File(filePath).exists();

                if (isComplete && fileExists) {
                    LinearLayout acts = row();
                    TextView openBtn = button("Open", false, () -> openHistoryFile(filePath, mime));
                    TextView shareBtn = button("Share", false, () -> shareHistoryFile(filePath, mime));
                    LinearLayout.LayoutParams bp1 = new LinearLayout.LayoutParams(0, dp(40), 1);
                    bp1.rightMargin = dp(4);
                    acts.addView(openBtn, bp1);
                    LinearLayout.LayoutParams bp2 = new LinearLayout.LayoutParams(0, dp(40), 1);
                    bp2.leftMargin = dp(4);
                    acts.addView(shareBtn, bp2);

                    LinearLayout.LayoutParams actParams = lp(-1, -2);
                    actParams.topMargin = dp(10);
                    r.addView(acts, actParams);
                } else if (isComplete) {
                    TextView purgedText = text("Temporary cached file removed", 11, false, muted);
                    LinearLayout.LayoutParams pp = lp(-1, -2);
                    pp.topMargin = dp(6);
                    r.addView(purgedText, pp);
                }

                LinearLayout.LayoutParams p = lp(-1, -2);
                p.bottomMargin = dp(10);
                body.addView(r, p);
            }

            body.addView(button("Clear all history", false, () -> {
                dialogBuilder()
                        .setTitle("Clear history")
                        .setMessage("Clear all conversion history entries?")
                        .setPositiveButton("Clear", (d, w) -> {
                            getPreferences(0).edit().putString("history", "[]").apply();
                            showPage("History");
                            toast("History cleared");
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }), layoutTop(8));
        } catch (Exception ignored) {}
    }

    private void openHistoryFile(String filePath, String mime) {
        if (filePath == null) {
            toast("File is no longer available in temporary cache");
            return;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            toast("File has been cleared from cache");
            return;
        }
        Uri uri = Uri.parse("content://" + getPackageName() + ".fileprovider/" + Uri.encode(file.getName()));
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime != null ? mime : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "Open file with"));
        } catch (ActivityNotFoundException e) {
            toast("No app can open this file type");
        }
    }

    private void shareHistoryFile(String filePath, String mime) {
        if (filePath == null) {
            toast("File is no longer available in temporary cache");
            return;
        }
        File file = new File(filePath);
        if (!file.exists()) {
            toast("File has been cleared from cache");
            return;
        }
        Uri uri = Uri.parse("content://" + getPackageName() + ".fileprovider/" + Uri.encode(file.getName()));
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(mime != null ? mime : "*/*");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, "Share file"));
    }

    private void confirmDeleteHistoryEntry(int index, String name) {
        dialogBuilder()
                .setTitle("Remove history item")
                .setMessage("Remove \"" + (name != null ? name : "this file") + "\" from conversion history?")
                .setPositiveButton("Remove", (d, w) -> deleteHistoryEntry(index))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deleteHistoryEntry(int index) {
        try {
            org.json.JSONArray old = new org.json.JSONArray(getPreferences(0).getString("history", "[]"));
            org.json.JSONArray updated = new org.json.JSONArray();
            for (int i = 0; i < old.length(); i++) if (i != index) updated.put(old.get(i));
            getPreferences(0).edit().putString("history", updated.toString()).apply();
            showPage("History");
        } catch (Exception ignored) {}
    }

    private void saveHistory(String name, String source, String target, long size, String status, String filePath, String mime) {
        if (!getPreferences(0).getBoolean("keep_history", true)) return;
        try {
            org.json.JSONArray a = new org.json.JSONArray(getPreferences(0).getString("history", "[]"));
            org.json.JSONArray n = new org.json.JSONArray();
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("name", name);
            o.put("source", source.toUpperCase(Locale.ROOT));
            o.put("target", target.toUpperCase(Locale.ROOT));
            o.put("size", size);
            o.put("status", status != null ? status : "complete");
            if (filePath != null) o.put("filePath", filePath);
            if (mime != null) o.put("mime", mime);
            o.put("date", System.currentTimeMillis());
            n.put(o);
            for (int i = 0; i < a.length() && i < 29; i++) n.put(a.get(i));
            getPreferences(0).edit().putString("history", n.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void addRecentShortcuts() {
        try {
            org.json.JSONArray history = new org.json.JSONArray(getPreferences(0).getString("history", "[]"));
            if (history.length() == 0) return;
            LinearLayout recent = column();
            TextView title = text("Recent conversions", 17, true, ink);
            recent.addView(title);
            for (int i = 0; i < Math.min(3, history.length()); i++) {
                org.json.JSONObject entry = history.getJSONObject(i);
                String target = entry.optString("target");
                TextView shortcut = button(entry.optString("name") + "  →  " + target, false, () -> {
                    preferredTarget = target.toLowerCase(Locale.ROOT);
                    openPicker();
                });
                LinearLayout.LayoutParams p = lp(-1, 46);
                p.topMargin = dp(8);
                recent.addView(shortcut, p);
            }
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(18);
            body.addView(recent, p);
        } catch (Exception ignored) {}
    }

    private View toggleCard(String title, String subtitle, boolean checked, CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = row();
        pad(row, 16, 14, 16, 14);
        row.setBackground(shape(surface, 16, line));
        LinearLayout textCol = column();
        textCol.addView(text(title, 15, true, ink));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, muted);
            LinearLayout.LayoutParams sp = lp(-1, -2);
            sp.topMargin = dp(3);
            textCol.addView(sub, sp);
        }
        row.addView(textCol, new LinearLayout.LayoutParams(0, -2, 1));
        Switch sw = new Switch(this);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        row.addView(sw, lp(-2, -2));
        return row;
    }

    private View settingsNavCard(String title, String subtitle, Runnable action) {
        LinearLayout card = row();
        pad(card, 16, 14, 16, 14);
        card.setBackground(shape(surface, 16, line));
        LinearLayout textCol = column();
        textCol.addView(text(title, 15, true, ink));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, false, muted);
            LinearLayout.LayoutParams sp = lp(-1, -2);
            sp.topMargin = dp(3);
            textCol.addView(sub, sp);
        }
        LinearLayout.LayoutParams tcp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        tcp.gravity = Gravity.CENTER_VERTICAL;
        card.addView(textCol, tcp);

        TextView arrow = text("›", 20, false, muted);
        arrow.setIncludeFontPadding(false);
        arrow.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ap.gravity = Gravity.CENTER_VERTICAL;
        card.addView(arrow, ap);

        card.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.98f).scaleY(.98f).setDuration(80).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            return false;
        });
        card.setOnClickListener(v -> {
            if (isClickDebounced()) return;
            if (action != null) action.run();
        });
        return card;
    }

    private void collectDeviceInfoOnce() {
        SharedPreferences prefs = getPreferences(0);
        if (!prefs.getBoolean("device_info_collected", false)) {
            prefs.edit()
                    .putString("device_manufacturer", Build.MANUFACTURER)
                    .putString("device_model", Build.MODEL)
                    .putString("device_android_version", "Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")")
                    .putLong("device_info_collected_at", System.currentTimeMillis())
                    .putBoolean("device_info_collected", true)
                    .apply();
        }
    }

    private void sendSupportEmail(String subject, String bodyText) {
        try {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:vermasankalp345@gmail.com"));
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"vermasankalp345@gmail.com"});
            intent.putExtra(Intent.EXTRA_SUBJECT, subject);
            if (bodyText != null && !bodyText.isEmpty()) {
                intent.putExtra(Intent.EXTRA_TEXT, bodyText);
            }
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            AlertDialog.Builder builder = dialogBuilder();
            builder.setTitle("No Email App Found");
            builder.setMessage("No compatible email client was detected on your device.\n\nPlease send your message manually to:\nvermasankalp345@gmail.com\n\nWould you like to copy the report text to your clipboard?");
            builder.setPositiveButton("Copy Text", (d, w) -> {
                ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("FileSwitch Message", bodyText != null ? bodyText : ""));
                    toast("Text copied to clipboard");
                }
            });
            builder.setNegativeButton("Close", null);
            builder.show();
        } catch (Exception e) {
            toast("Could not open email client");
        }
    }

    private Dialog unifiedConsentDialog;

    private boolean verifyFileAccess() {
        try {
            File dir = getAccessibleFileSwitchDir();
            if (dir == null) return false;
            if (!dir.exists() && !dir.mkdirs()) {
                dir = getExternalFilesDir("FileSwitch");
                if (dir == null || (!dir.exists() && !dir.mkdirs())) {
                    dir = new File(getCacheDir(), "fileswitch");
                    if (!dir.exists() && !dir.mkdirs()) return false;
                }
            }
            if (!dir.canWrite()) return false;
            File testFile = new File(dir, ".fileswitch_verify_" + System.currentTimeMillis() + ".tmp");
            boolean created = testFile.createNewFile();
            if (created) {
                testFile.delete();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private void showSplashOrConsent() {
        setPalette();
        boolean dark = isDark();
        int splashBg = dark ? bg : Color.parseColor("#245CCB");
        int splashInk = dark ? ink : Color.WHITE;
        int splashMuted = dark ? muted : Color.parseColor("#D7E2F4");
        getWindow().setStatusBarColor(splashBg);
        getWindow().setNavigationBarColor(splashBg);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : 0);
        LinearLayout splashRoot = column();
        splashRoot.setBackgroundColor(splashBg);
        splashRoot.setGravity(Gravity.CENTER_HORIZONTAL);
        setContentView(splashRoot);

        LinearLayout centerBox = column();
        centerBox.setGravity(Gravity.CENTER);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.fileswitch_splash_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        centerBox.addView(logo, lp(160, 160));

        TextView appTitle = text("FileSwitch", 32, true, splashInk);
        LinearLayout.LayoutParams tp = lp(-2, -2);
        tp.topMargin = dp(18);
        centerBox.addView(appTitle, tp);

        splashRoot.addView(centerBox, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout footer = column();
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        pad(footer, 0, 0, 0, 40);

        TextView fromText = text("by", 13, false, splashMuted);
        fromText.setGravity(Gravity.CENTER);
        fromText.setLetterSpacing(0.08f);
        footer.addView(fromText);

        TextView authorText = text("Sankalp", 17, true, splashInk);
        authorText.setGravity(Gravity.CENTER);
        authorText.setLetterSpacing(0.12f);
        LinearLayout.LayoutParams ap = lp(-2, -2);
        ap.topMargin = dp(2);
        footer.addView(authorText, ap);

        splashRoot.addView(footer, lp(-1, -2));
        page = "Consent";
        showUnifiedConsentPopup(false);
    }

    private void showUnifiedConsentPopup(boolean isWarning) {
        if (isFinishing()) return;
        if (unifiedConsentDialog != null && unifiedConsentDialog.isShowing()) {
            unifiedConsentDialog.dismiss();
        }

        Dialog dialog = new Dialog(this);
        unifiedConsentDialog = dialog;
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout card = column();
        pad(card, 16, 14, 16, 14);
        card.setBackground(shape(surface, 18, line));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(12));
        }

        String fullText = isWarning
                ? "Without file access and agreeing to the Privacy Policy and Terms of Use, FileSwitch cannot perform its functions. Please allow access to continue."
                : "By continuing, you agree to the Privacy Policy and Terms of Use and allow FileSwitch to access the files you select for processing.";

        SpannableString ss = new SpannableString(fullText);

        String ppStr = "Privacy Policy";
        int ppStart = fullText.indexOf(ppStr);
        if (ppStart >= 0) {
            int ppEnd = ppStart + ppStr.length();
            ss.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    dialog.dismiss();
                    showPage("Settings_PrivacyPolicy");
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(accent);
                    ds.setUnderlineText(false);
                }
            }, ppStart, ppEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new StyleSpan(Typeface.BOLD), ppStart, ppEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(accent), ppStart, ppEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        String touStr = "Terms of Use";
        int touStart = fullText.indexOf(touStr);
        if (touStart >= 0) {
            int touEnd = touStart + touStr.length();
            ss.setSpan(new ClickableSpan() {
                @Override
                public void onClick(View widget) {
                    dialog.dismiss();
                    showPage("Settings_Terms");
                }

                @Override
                public void updateDrawState(TextPaint ds) {
                    ds.setColor(accent);
                    ds.setUnderlineText(false);
                }
            }, touStart, touEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new StyleSpan(Typeface.BOLD), touStart, touEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            ss.setSpan(new ForegroundColorSpan(accent), touStart, touEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        TextView desc = text("", 13, false, muted);
        desc.setText(ss);
        desc.setMovementMethod(LinkMovementMethod.getInstance());
        desc.setHighlightColor(Color.TRANSPARENT);
        desc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dp = lp(-1, -2);
        dp.bottomMargin = dp(12);
        card.addView(desc, dp);

        LinearLayout btnRow = row();

        String declineLabel = isWarning ? "Exit" : "Decline";
        TextView declineBtn = button(declineLabel, false, () -> {
            dialog.dismiss();
            if (isWarning) {
                finishAffinity();
            } else {
                showUnifiedConsentPopup(true);
            }
        });
        declineBtn.setMinHeight(dp(44));
        declineBtn.setTextSize(13);
        pad(declineBtn, 6, 0, 6, 0);

        TextView allowBtn = button("Allow & Continue", true, () -> {
            boolean granted = verifyFileAccess();
            if (!granted) {
                toast("File access could not be initialized");
                dialog.dismiss();
                showUnifiedConsentPopup(true);
                return;
            }
            dialog.dismiss();
            getPreferences(0).edit()
                    .putBoolean("consent_accepted", true)
                    .putBoolean("storage_consent_accepted", true)
                    .apply();
            showShortLoading(() -> showPage("Convert"));
        });
        allowBtn.setMinHeight(dp(44));
        allowBtn.setTextSize(13);
        pad(allowBtn, 6, 0, 6, 0);

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        btnRow.addView(declineBtn, dlp);

        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(44), 1.25f);
        alp.leftMargin = dp(10);
        btnRow.addView(allowBtn, alp);

        card.addView(btnRow, lp(-1, -2));

        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            wlp.y = dp(28);
            window.setAttributes(wlp);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.90f);
            window.setLayout(Math.min(width, dp(380)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    private TextView sectionHeading(String emoji, String title) {
        TextView tv = text("", 12, true, accent);
        if (emoji == null || emoji.isEmpty()) {
            tv.setText(title);
        } else {
            String full = emoji + "  " + title;
            android.text.SpannableString ss = new android.text.SpannableString(full);
            ss.setSpan(new android.text.style.RelativeSizeSpan(1.35f), 0, emoji.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(ss);
        }
        tv.setLetterSpacing(0.08f);
        tv.setIncludeFontPadding(false);
        pad(tv, 2, 0, 0, 0);
        return tv;
    }

    private void renderSettings() {
        heading("Settings", "Preferences, privacy, support and app information.");

        // 1. Theme
        TextView themeTitle = sectionHeading("📱", "THEME");
        body.addView(themeTitle);

        TextView themeLabel = text("Theme", 15, true, ink);
        pad(themeLabel, 2, 0, 0, 0);
        body.addView(themeLabel, layoutTop(8));
        Spinner s = new Spinner(this);
        String[] displayOptions = {"System Default", "Light", "Dark"};
        s.setAdapter(createThemedAdapter(displayOptions));
        int currentSelection = "Dark".equals(themeChoice) ? 2 : "Light".equals(themeChoice) ? 1 : 0;
        s.setSelection(currentSelection);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String choice = pos == 1 ? "Light" : pos == 2 ? "Dark" : "System";
                if (first) {
                    first = false;
                    return;
                }
                if (!choice.equals(themeChoice)) {
                    themeChoice = choice;
                    getPreferences(0).edit().putString("theme", choice).apply();
                    showPage("Settings");
                }
            }
        });
        body.addView(s, layoutTop(4));

        // 2. Preferences
        TextView prefTitle = sectionHeading("⚙️", "PREFERENCES");
        body.addView(prefTitle, layoutTop(28));

        TextView qualLabel = text("Default image quality (" + imageQuality + "%)", 15, true, ink);
        pad(qualLabel, 2, 0, 0, 0);
        body.addView(qualLabel, layoutTop(8));
        SeekBar qualBar = new SeekBar(this);
        qualBar.setMax(80);
        qualBar.setProgress(imageQuality - 20);
        qualBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStartTrackingTouch(SeekBar bar) {}
            public void onStopTrackingTouch(SeekBar bar) {
                getPreferences(0).edit().putInt("default_quality", imageQuality).apply();
            }
            public void onProgressChanged(SeekBar bar, int value, boolean user) {
                imageQuality = 20 + value;
                qualLabel.setText("Default image quality (" + imageQuality + "%)");
            }
        });
        LinearLayout.LayoutParams qbp = lp(-1, 40);
        qbp.topMargin = dp(2);
        body.addView(qualBar, qbp);

        TextView dpiLabel = text("Default resolution", 15, true, ink);
        pad(dpiLabel, 2, 0, 0, 0);
        body.addView(dpiLabel, layoutTop(14));
        Spinner dpiSpin = new Spinner(this);
        Integer[] dpiOpts = {72, 96, 150, 300, 600};
        String[] dpiTitles = {"72 DPI", "96 DPI", "150 DPI", "300 DPI", "600 DPI"};
        dpiSpin.setAdapter(createThemedAdapter(dpiTitles));
        dpiSpin.setSelection(Math.max(0, Arrays.asList(dpiOpts).indexOf(outputDpi)));
        dpiSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                outputDpi = dpiOpts[pos];
                getPreferences(0).edit().putInt("default_dpi", outputDpi).apply();
            }
        });
        body.addView(dpiSpin, layoutTop(4));

        TextView pdfCompLabel = text("Default PDF compression", 15, true, ink);
        pad(pdfCompLabel, 2, 0, 0, 0);
        body.addView(pdfCompLabel, layoutTop(14));
        Spinner pdfCompSpin = new Spinner(this);
        String[] pdfCompTitles = {"Light compression (Reduce size: 30%)", "Balanced compression (Reduce size: 70%)", "Maximum compression (Reduce size: 90%)"};
        pdfCompSpin.setAdapter(createThemedAdapter(pdfCompTitles));
        int savedPdfComp = getPreferences(0).getInt("default_pdf_compression_level", 70);
        int spinIdx = savedPdfComp <= 40 ? 0 : savedPdfComp <= 80 ? 1 : 2;
        pdfCompSpin.setSelection(spinIdx);
        pdfCompSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                int level = pos == 0 ? 30 : pos == 1 ? 70 : 90;
                getPreferences(0).edit().putInt("default_pdf_compression_level", level).apply();
            }
        });
        body.addView(pdfCompSpin, layoutTop(4));

        boolean autoOpen = getPreferences(0).getBoolean("auto_open_result", false);
        View autoOpenCard = toggleCard("Auto-open result", "Automatically open converted file in default viewer", autoOpen, (cb, checked) -> {
            getPreferences(0).edit().putBoolean("auto_open_result", checked).apply();
        });
        body.addView(autoOpenCard, layoutTop(14));

        boolean preserveMeta = getPreferences(0).getBoolean("preserve_metadata", true);
        View metaCard = toggleCard("Metadata preservation", "Preserve camera EXIF and timestamps in converted images", preserveMeta, (cb, checked) -> {
            getPreferences(0).edit().putBoolean("preserve_metadata", checked).apply();
        });
        body.addView(metaCard, layoutTop(8));

        boolean keepHist = getPreferences(0).getBoolean("keep_history", true);
        View histCard = toggleCard("Save conversion history", "Log recent conversions locally on this device", keepHist, (cb, checked) -> {
            getPreferences(0).edit().putBoolean("keep_history", checked).apply();
        });
        body.addView(histCard, layoutTop(8));

        // 3. Files & Storage
        TextView storageTitle = sectionHeading("📁", "FILES & STORAGE");
        body.addView(storageTitle, layoutTop(28));
        body.addView(settingsNavCard("Output Location & Storage Info", "Where generated files are saved on your device", () -> showPage("Settings_StorageInfo")), layoutTop(8));

        // 4. Privacy & Security
        TextView privacyTitle = sectionHeading("🔒", "PRIVACY & SECURITY");
        body.addView(privacyTitle, layoutTop(28));
        body.addView(settingsNavCard("Privacy Policy", "Legal privacy disclosures, permissions, and data practices", () -> showPage("Settings_PrivacyPolicy")), layoutTop(8));
        body.addView(settingsNavCard("Terms of Use", "Terms, conditions, and fair-use policy", () -> showPage("Settings_Terms")), layoutTop(8));

        // 5. Help & Support
        TextView helpTitle = sectionHeading("🛠️", "HELP & SUPPORT");
        body.addView(helpTitle, layoutTop(28));
        body.addView(settingsNavCard("Help & Support", "Guides for conversion, PDF tools and troubleshooting", () -> showPage("Settings_HelpSupport")), layoutTop(8));
        body.addView(settingsNavCard("Report a Problem", "Diagnose an issue and send a detailed report", () -> showPage("Settings_ReportProblem")), layoutTop(8));
        body.addView(settingsNavCard("Send Feedback", "Email your suggestions to vermasankalp345@gmail.com", () -> sendSupportEmail("FileSwitch Feedback", "Hi Sankalp,\n\nHere is my feedback for FileSwitch:\n\n")), layoutTop(8));

        // 6. About
        TextView aboutTitle = sectionHeading("ℹ️", "ABOUT");
        body.addView(aboutTitle, layoutTop(28));
        body.addView(settingsNavCard("About FileSwitch", "Version, developer details and features", () -> showPage("Settings_About")), layoutTop(8));
        body.addView(settingsNavCard("Open Source Licenses", "Third-party libraries used in FileSwitch", () -> showPage("Settings_Licenses")), layoutTop(8));
        body.addView(settingsNavCard("Copyright", "© 2026 Sankalp Verma. All rights reserved.", () -> showPage("Settings_Copyright")), layoutTop(8));
    }

    private void renderSettingsPrivacyPolicy() {
        heading("Privacy Policy", "Last updated: October 2026");

        String[][] sections = {
                {"1. Introduction",
                        "FileSwitch (\"we\", \"our\", or \"the application\"), developed by Sankalp Verma, is a local, privacy-first file conversion and editing utility for Android.\n\nWe believe your personal documents and media files belong exclusively to you. This Privacy Policy details our data practices, our strictly local on-device processing architecture, application permissions, and our commitment to transparency and user control."},

                {"2. Information & Scoped File Access",
                        "FileSwitch uses Android's modern Storage Access Framework (SAF) and system document picker. The application does NOT request broad storage permissions (such as READ_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE).\n\nAccess is granted temporarily and strictly to the specific files that you explicitly choose or share into FileSwitch via standard system share intents. FileSwitch does not scan your storage in the background, does not index your media folders, and cannot access files you have not selected."},

                {"3. Local On-Device Processing & Zero Tracking",
                        "All file conversions, image modifications, OCR text extractions, document formatting, compression, and PDF operations are executed 100% locally on your device hardware using your device's native processor and memory.\n\nFileSwitch does NOT upload, transmit, or stream your files, text, images, or documents to any remote server or cloud infrastructure. Furthermore, FileSwitch contains no advertising SDKs, no tracking analytics, no crash-reporting telemetry, and no background uploaders."},

                {"4. Data Storage & Output Files",
                        "When an operation completes, the resulting file is saved directly into the user-accessible FileSwitch folder in your device's internal storage (located in Documents/FileSwitch or Download/FileSwitch).\n\nTemporary cache files generated during conversion reside in the application's isolated cache sandbox and are automatically pruned within 24 hours. FileSwitch never persists file byte data or conversion content in long-term logs."},

                {"5. Device Information Collection",
                        "Upon first initialization, FileSwitch records basic device characteristics locally in application preferences:\n• Device Manufacturer\n• Device Model\n• Android OS Version & API Level\n\nThis information remains on your device and is used solely for local UI adaptation, hardware format compatibility, and diagnosing issues if you submit an optional problem report. It is never linked to your personal identity and is never transmitted automatically."},

                {"6. Application & Platform Permissions",
                        "FileSwitch operates with minimal platform permissions:\n\n• Scoped Document Access: Temporary, read-only content URIs for files chosen via Android's document picker without broad storage permissions.\n• Notifications (Optional, Android 13+): Used exclusively to display active background conversion progress and completion alerts. It is never used for marketing, advertising, or background tracking.\n• Zero Network Permissions: FileSwitch does NOT declare the INTERNET permission in its manifest. The application has zero technical capability to communicate with external servers or send data across the network."},

                {"7. Data Security & Sandbox Isolation",
                        "Because all processing occurs on-device, your files are protected by Android's application sandboxing security model. Shared output files are exposed via secure content providers with temporary read-only permissions granted strictly to the target application you choose when sharing or opening files."},

                {"8. Third-Party Libraries & Services",
                        "FileSwitch integrates trusted open-source libraries to perform offline file parsing:\n• Apache PDFBox-Android (PDF operations)\n• AndroidSVG (Vector image rendering)\n• Google ML Kit Text Recognition (On-device OCR)\n• AndroidX ExifInterface (EXIF metadata)\n• Apache POI Android (Office documents)\n\nAll libraries run strictly in offline mode without telemetry, tracking SDKs, advertising frameworks, or external communication."},

                {"9. Children's Privacy",
                        "FileSwitch does not collect personal information from any user, including children under the age of 13. The application is completely safe for general audiences and operates as a standalone utility."},

                {"10. Changes to This Privacy Policy",
                        "We may update this Privacy Policy to reflect future features or regulatory requirements. Any revisions will be published within the application along with an updated effective date."},

                {"11. Contact & Inquiries",
                        "If you have questions, feedback, or concerns regarding this Privacy Policy or your data, please contact developer Sankalp Verma directly at:\n\nvermasankalp345@gmail.com"}
        };

        LinearLayout docCard = column();
        pad(docCard, 18, 18, 18, 18);
        int cardBg = Color.argb(isDark() ? 222 : 230, Color.red(surface), Color.green(surface), Color.blue(surface));
        docCard.setBackground(shape(cardBg, 16, line));

        for (int i = 0; i < sections.length; i++) {
            TextView title = text(sections[i][0], 15, true, ink);
            if (i > 0) {
                LinearLayout.LayoutParams tp = lp(-1, -2);
                tp.topMargin = dp(20);
                docCard.addView(title, tp);
            } else {
                docCard.addView(title);
            }

            TextView desc = text(sections[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams dp = lp(-1, -2);
            dp.topMargin = dp(6);
            docCard.addView(desc, dp);
        }

        body.addView(docCard, layoutTop(8));
        body.addView(button("Contact Developer Regarding Privacy", false, () -> sendSupportEmail("FileSwitch Privacy Inquiry", "Hi Sankalp,\n\nI have a question regarding the FileSwitch Privacy Policy:\n\n")), layoutTop(16));
    }

    private void renderSettingsTerms() {
        heading("Terms of Use", "Last updated: October 2026");

        String[][] terms = {
                {"1. Acceptance of Terms",
                        "By downloading, installing, accessing, or using FileSwitch (\"the application\"), you agree to be legally bound by these Terms of Use. If you do not agree to these terms, please do not use FileSwitch."},

                {"2. Use of FileSwitch",
                        "Developer Sankalp Verma grants you a personal, non-exclusive, non-transferable, revocable license to use FileSwitch on your compatible Android devices in accordance with these terms for personal, academic, or professional file utility purposes."},

                {"3. Supported Files & Conversions",
                        "FileSwitch provides local file conversion, optical character recognition (OCR), metadata management, image editing, archive creation/extraction, and PDF manipulation.\n\nWhile FileSwitch supports a wide range of standard document, image, audio/video container, and archive formats, processing capabilities are subject to device hardware, memory limits, and file integrity."},

                {"4. User Responsibilities",
                        "You agree to use FileSwitch solely for lawful purposes. You represent and warrant that you hold all necessary copyrights, licenses, and legal permissions for any files, documents, or media you select, convert, or modify with FileSwitch."},

                {"5. Local & Offline Processing",
                        "FileSwitch performs operations locally on your device without relying on cloud infrastructure. You acknowledge that conversion performance, processing speed, and file size limits depend on your device's hardware specifications (CPU, RAM, storage)."},

                {"6. Storage, Permissions & Generated Files",
                        "Files created or converted by FileSwitch are automatically stored in the user-accessible FileSwitch folder in your device's internal storage. FileSwitch accesses only the files you explicitly provide through system document pickers without requesting broad storage permissions. You are solely responsible for managing your device storage capacity, organizing saved files, and maintaining backups of critical data."},

                {"7. Intellectual Property",
                        "FileSwitch, including its user interface, source code, visual branding, application icon, and algorithms (excluding third-party open-source libraries), is the proprietary intellectual property of developer Sankalp Verma. © 2026 Sankalp Verma. All rights reserved.\n\nThird-party open-source components remain licensed under their respective Apache 2.0 and open-source licenses as documented in the Open Source Licenses section."},

                {"8. Disclaimer of Warranties",
                        "FileSwitch is provided on an \"AS IS\" and \"AS AVAILABLE\" basis without warranties of any kind, whether express or implied, including but not limited to implied warranties of merchantability, fitness for a particular purpose, conversion accuracy, or non-infringement.\n\nWhile every effort is made to ensure accurate and reliable file conversion, we do not warrant that operations will be completely error-free or uninterrupted."},

                {"9. Limitation of Liability",
                        "To the fullest extent permitted by applicable law, developer Sankalp Verma shall not be liable for any direct, indirect, incidental, special, exemplary, or consequential damages, including but not limited to damages for loss of files, corrupted data, device malfunction, or business interruption arising out of your use or inability to use FileSwitch."},

                {"10. Changes to Terms",
                        "We reserve the right to revise or update these Terms of Use at any time. Continued use of FileSwitch following any modifications constitutes your acceptance of the updated terms."},

                {"11. Contact Information",
                        "For questions, support, or legal inquiries regarding these Terms of Use, please contact developer Sankalp Verma at:\n\nvermasankalp345@gmail.com"}
        };

        LinearLayout docCard = column();
        pad(docCard, 18, 18, 18, 18);
        int cardBg = Color.argb(isDark() ? 222 : 230, Color.red(surface), Color.green(surface), Color.blue(surface));
        docCard.setBackground(shape(cardBg, 16, line));

        for (int i = 0; i < terms.length; i++) {
            TextView title = text(terms[i][0], 15, true, ink);
            if (i > 0) {
                LinearLayout.LayoutParams tp = lp(-1, -2);
                tp.topMargin = dp(20);
                docCard.addView(title, tp);
            } else {
                docCard.addView(title);
            }

            TextView desc = text(terms[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams dp = lp(-1, -2);
            dp.topMargin = dp(6);
            docCard.addView(desc, dp);
        }

        body.addView(docCard, layoutTop(8));
        body.addView(button("Contact Developer Regarding Terms", false, () -> sendSupportEmail("FileSwitch Terms Inquiry", "Hi Sankalp,\n\nI have a question regarding the FileSwitch Terms of Use:\n\n")), layoutTop(16));
    }

    private void renderSettingsHelpSupport() {
        heading("Help & Support", "Guides, FAQ and troubleshooting for FileSwitch.");

        body.addView(settingsNavCard("Getting Started", "Learn the core FileSwitch workflow and features", () -> showPage("Settings_Help_GettingStarted")), layoutTop(8));
        body.addView(settingsNavCard("Converting Files", "Selecting files, choosing formats/options, and results", () -> showPage("Settings_Help_Converting")), layoutTop(8));
        body.addView(settingsNavCard("Where Files Are Saved", "Locate generated files in the FileSwitch folder", () -> showPage("Settings_Help_Storage")), layoutTop(8));
        body.addView(settingsNavCard("Custom Filenames", "How custom naming and automatic naming work", () -> showPage("Settings_Help_Filenames")), layoutTop(8));
        body.addView(settingsNavCard("PDF Tools", "Guide to merge, split, compress, watermark, and edit PDFs", () -> showPage("Settings_Help_PdfTools")), layoutTop(8));
        body.addView(settingsNavCard("Troubleshooting Failed Conversions", "Solutions for common errors, passwords, and fixes", () -> showPage("Settings_Help_Troubleshooting")), layoutTop(8));
        body.addView(settingsNavCard("Need Further Assistance?", "Direct support contact and problem reporting", () -> showPage("Settings_Help_Assistance")), layoutTop(8));

        body.addView(button("Report a Problem", true, () -> showPage("Settings_ReportProblem")), layoutTop(18));
        body.addView(button("Contact Developer (Email)", false, () -> sendSupportEmail("FileSwitch Support Request", "Hi Sankalp,\n\nI need help with FileSwitch:\n\n")), layoutTop(8));
    }

    private void renderHelpGettingStarted() {
        heading("Getting Started", "Learn the core workflow and features of FileSwitch.");

        String[][] cards = {
                {"Welcome to FileSwitch",
                        "FileSwitch is a fast, universal, 100% offline file converter and document utility designed natively for Android.\n\nAll file conversions, image modifications, OCR text extractions, and PDF tools run entirely on your device hardware without using remote cloud servers or third-party tracking services."},

                {"The Three-Step Workflow",
                        "Converting files with FileSwitch is simple and straightforward:\n\n1. Select Files: Tap 'Select files' on the Convert screen or drag and drop any supported document, image, or archive.\n\n2. Choose Target Format: Pick your desired output format from the grid or category dropdown.\n\n3. Convert & Save: Tap 'Convert Batch'. FileSwitch converts your files locally in seconds and automatically saves them into your device's FileSwitch folder."},

                {"Key App Sections",
                        "• Convert: Universal batch conversion engine for images, documents, audio containers, and archives.\n• Tools: Dedicated PDF manipulation suite, image editing, compression, OCR, and document tools.\n• History: Local log of recent conversions with quick share, open, and file info access.\n• Settings: Adjust theme, default DPI, compression quality, metadata preservation, and access support."},

                {"Privacy & Offline First",
                        "Because FileSwitch does not declare network permissions, your files never leave your phone. You can use all features anytime, anywhere, even in Airplane Mode without an internet connection."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Go to Convert Screen", true, () -> showPage("Convert")), layoutTop(16));
        body.addView(button("Explore PDF Tools", false, () -> showPage("Tools")), layoutTop(8));
    }

    private void renderHelpConverting() {
        heading("Converting Files", "Guide to selecting files, choosing formats, and managing results.");

        String[][] cards = {
                {"Selecting Files & Drag-and-Drop",
                        "• System Document Picker: Tap 'Select files' to open Android's system picker and select one or multiple files.\n• Drag & Drop: Drag images or documents directly into FileSwitch in split-screen or tablet multi-window mode.\n• System Share Sheet: Share files directly into FileSwitch from your Gallery, Files app, or messaging apps."},

                {"Supported Formats",
                        "• Images: PNG, JPG/JPEG, WEBP, GIF, BMP, SVG, TIFF, HEIC/AVIF.\n• Documents: PDF, DOCX, XLSX, PPTX, TXT, HTML, Markdown (MD), RTF, CSV, JSON, XML.\n• Archives: ZIP, TAR, GZ, TGZ extraction and compression."},

                {"Conversion Settings & Quality",
                        "• Image Compression Quality: Choose from Lossless, High (90%), Balanced (80%), or Maximum (60%) in Settings.\n• PDF Resolution (DPI): Select 72 DPI (compact/screen), 150 DPI (balanced), or 300 DPI (high-resolution printing).\n• Metadata (EXIF): Choose whether to preserve camera metadata and timestamps in Settings."},

                {"Batch Processing",
                        "Select multiple files at once and tap 'Convert Batch'. FileSwitch will process each file in sequence with real-time status and progress indicators."},

                {"Managing & Sharing Results",
                        "When conversion completes, you can:\n• Tap 'Open' to view the file in your default viewer.\n• Tap 'Share' to send the file via email, messaging, or cloud storage.\n• View all recent outputs anytime in the History tab."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Start Converting Now", true, () -> showPage("Convert")), layoutTop(16));
    }

    private void renderHelpStorage() {
        heading("Where Files Are Saved", "Find your generated files and manage on-device storage.");

        String[][] cards = {
                {"Automatic Saving in Dedicated Folder",
                        "Whenever an operation generates an output file (conversion, PDF tool, image crop, compression, etc.), FileSwitch automatically saves the final file directly into the dedicated 'FileSwitch' folder in your device's internal storage."},

                {"Exact Storage Directory Paths",
                        "Depending on your Android version and manufacturer, your files are stored in:\n\n• Primary Location: Documents / FileSwitch /\n• Fallback Location: Download / FileSwitch /\n\nYou can open any file manager (Google Files, Samsung My Files, Solid Explorer, etc.) and navigate to Documents → FileSwitch to access your files directly."},

                {"Duplicate Prevention & File Safety",
                        "FileSwitch will never accidentally overwrite your existing files. If a file with the same name already exists in the FileSwitch folder, FileSwitch automatically adds an incremental number (such as 'document (2).pdf')."},

                {"Temporary Cache Sandbox",
                        "Intermediate processing files are stored in a private temporary cache folder inside the application sandbox. FileSwitch automatically prunes temporary files older than 24 hours to prevent storage clutter."},

                {"Zero Cloud Uploads",
                        "All files remain 100% on your device. FileSwitch does not use cloud storage, meaning only you have access to your converted documents."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("View Storage Info in Settings", false, () -> showPage("Settings_StorageInfo")), layoutTop(16));
    }

    private void renderHelpFilenames() {
        heading("Custom Filenames", "How output file naming and automatic naming work in FileSwitch.");

        String[][] cards = {
                {"Custom Output Filenames",
                        "On the Convert screen or inside any Tool page, you can enter a custom name in the 'Output Filename' input field before processing."},

                {"Clean Naming Guarantee",
                        "When you enter a custom name (e.g. 'ProjectPlan'), FileSwitch names your generated file exactly as specified ('ProjectPlan.pdf').\n\nFileSwitch does NOT prepend random UUIDs, hash strings, or timestamps to your custom filename."},

                {"Automatic Default Naming",
                        "If you leave the custom filename field empty, FileSwitch automatically generates a clean, descriptive name based on the original file name:\n• image.png → image.jpg\n• document.pdf → document-page-1.png\n• archive.zip → extracted files named according to archive contents."},

                {"Batch Naming Rules",
                        "When converting multiple files with a custom name entered, FileSwitch systematically appends an incrementing index (e.g. 'Invoice-1.pdf', 'Invoice-2.pdf') to keep your batch organized."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Try Custom Naming in Convert", true, () -> showPage("Convert")), layoutTop(16));
    }

    private void renderHelpPdfTools() {
        heading("PDF Tools", "Guide to specialized PDF manipulation and enhancement tools.");

        String[][] cards = {
                {"Merge & Split PDFs",
                        "• Merge PDFs: Select two or more PDF files to combine them into a single continuous document.\n• Split PDF: Extract specific page ranges (e.g., '1-3, 5, 8-12') or split a document into individual pages."},

                {"Page Organization & Layout",
                        "• Extract Pages: Choose exact pages to create a new focused PDF.\n• Rotate Pages: Correct orientation by rotating pages 90°, 180°, or 270°.\n• Resize PDF: Convert pages to standard paper sizes including A4, US Letter, Legal, and A3."},

                {"Security, Passwords & Unlock",
                        "• Protect PDF: Secure sensitive PDFs with strong password encryption.\n• Unlock PDF: Decrypt password-protected PDFs to enable printing, copying, and converting."},

                {"Optimization & Customization",
                        "• Compress PDF: Reduce file size for easy email sharing.\n• Add Watermark: Stamp custom text watermarks with adjustable angle, opacity, and color.\n• Insert Image: Place images with freeform drag, resize, and positioning on any page.\n• Digital Signature: Draw your signature and place it securely on PDF documents."},

                {"OCR & Text Extraction",
                        "• Extract Text: Pull all selectable text from PDF documents into TXT, HTML, or DOCX.\n• OCR Recognition: Scan images and non-selectable PDFs to recognize text on-device."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Open Tools Tab", true, () -> showPage("Tools")), layoutTop(16));
    }

    private void renderHelpTroubleshooting() {
        heading("Troubleshooting", "Practical solutions to common conversion issues and errors.");

        String[][] cards = {
                {"Conversion Failed or Errored",
                        "• Verify File Integrity: Make sure the input file is not corrupt and can be opened in its default viewer.\n• Unsupported Variant: Certain exotic or proprietary sub-formats may not be supported. Try converting the file to standard PNG, PDF, or DOCX first."},

                {"Password-Protected or Encrypted PDFs",
                        "If a PDF is password-protected, the standard conversion engine cannot read its content.\n\nSolution: Go to Tools → Unlock PDF, enter the document password to create an unlocked copy, and then proceed with conversion."},

                {"Out of Memory on Large Files",
                        "Processing very large files (50+ page PDFs or high-megapixel images) requires substantial RAM.\n\nSolutions:\n• In Settings, lower the Default DPI from 300 to 150 DPI.\n• Use Tools → Split PDF to process documents in smaller page batches."},

                {"Storage Space Full",
                        "If conversion fails with a write error, check your device's internal storage. Ensure you have at least 200 MB of free storage space for temporary processing and saved outputs."},

                {"File Access Revoked",
                        "If a file was selected a long time ago, Android's temporary permission grant may have expired. Simply tap 'Select files' and pick the file again."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Report a Problem to Developer", true, () -> showPage("Settings_ReportProblem")), layoutTop(16));
        body.addView(button("Contact Support via Email", false, () -> sendSupportEmail("FileSwitch Troubleshooting Help", "Hi Sankalp,\n\nI need help troubleshooting an issue in FileSwitch:\n\n")), layoutTop(8));
    }

    private void renderHelpAssistance() {
        heading("Need Further Assistance?", "Get in touch with the developer, report bugs, or ask questions.");

        String[][] cards = {
                {"Direct Developer Support",
                        "FileSwitch is actively maintained by developer Sankalp Verma. If you run into an issue, need support with a specific file format, or want to suggest new tools, assistance is readily available."},

                {"Email Contact",
                        "You can email developer Sankalp Verma directly at:\n\nvermasankalp345@gmail.com\n\nPlease include details about your device model, Android OS version, and the file format you are working with for quick assistance."},

                {"In-App Problem Reporting",
                        "Use the built-in 'Report a Problem' tool to generate a pre-formatted diagnostics report with optional device details (device manufacturer, model, and Android version)."}
        };

        for (int i = 0; i < cards.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(cards[i][0], 15, true, ink));
            TextView desc = text(cards[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Send Email to Developer", true, () -> sendSupportEmail("FileSwitch Support Request", "Hi Sankalp,\n\nI need help with FileSwitch:\n\n")), layoutTop(16));
        body.addView(button("Open Problem Report Form", false, () -> showPage("Settings_ReportProblem")), layoutTop(8));
    }

    private void renderSettingsReportProblem() {
        heading("Report a Problem", "Describe an issue so we can help resolve it.");

        LinearLayout formCard = column();
        pad(formCard, 16, 16, 16, 16);
        formCard.setBackground(shape(surface, 16, line));

        // 1. Problem Category
        formCard.addView(text("Problem Category", 14, true, ink));
        Spinner catSpinner = new Spinner(this);
        String[] categories = {
                "Conversion Failed",
                "File Not Opening",
                "PDF Problem",
                "Import / Export Problem",
                "Performance / Slowness",
                "UI / Display Problem",
                "Permission / Storage Problem",
                "Other"
        };
        catSpinner.setAdapter(createThemedAdapter(categories));
        formCard.addView(catSpinner, layoutTop(6));

        // 2. Describe the Problem
        TextView descLabel = text("Describe the Problem", 14, true, ink);
        formCard.addView(descLabel, layoutTop(14));
        TextView descSub = text("Explain what happened, steps to reproduce, or any error shown", 12, false, muted);
        formCard.addView(descSub, layoutTop(2));
        EditText descInput = new EditText(this);
        descInput.setHint("What went wrong? Steps to reproduce the issue...");
        descInput.setMinLines(4);
        descInput.setMaxLines(8);
        descInput.setGravity(Gravity.TOP | Gravity.START);
        descInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleEditText(descInput);
        pad(descInput, 12, 10, 12, 10);
        formCard.addView(descInput, layoutTop(6));

        // 3. Additional Details (Optional)
        TextView detailsLabel = text("Additional Details (Optional)", 14, true, ink);
        formCard.addView(detailsLabel, layoutTop(14));
        TextView detailsSub = text("Affected file format, file size, or specific tool used", 12, false, muted);
        formCard.addView(detailsSub, layoutTop(2));
        EditText detailsInput = new EditText(this);
        detailsInput.setHint("e.g. Input: PDF (15 MB), Output: PNG, Tool: Split PDF");
        detailsInput.setMinLines(2);
        detailsInput.setMaxLines(4);
        detailsInput.setGravity(Gravity.TOP | Gravity.START);
        detailsInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        styleEditText(detailsInput);
        pad(detailsInput, 12, 10, 12, 10);
        formCard.addView(detailsInput, layoutTop(6));

        // 4. Device Details Checkbox
        CheckBox includeDiag = new CheckBox(this);
        includeDiag.setText("Include device info (Model: " + Build.MODEL + ", Android " + Build.VERSION.RELEASE + ")");
        includeDiag.setTextColor(ink);
        includeDiag.setTextSize(13);
        includeDiag.setChecked(true);
        formCard.addView(includeDiag, layoutTop(14));

        body.addView(formCard, layoutTop(8));

        body.addView(button("Send Problem Report", true, () -> {
            String category = categories[catSpinner.getSelectedItemPosition()];
            String desc = descInput.getText().toString().trim();
            if (desc.isEmpty()) {
                toast("Please enter a description of the problem");
                descInput.requestFocus();
                return;
            }
            String details = detailsInput.getText().toString().trim();
            StringBuilder bodyBuilder = new StringBuilder();
            bodyBuilder.append("--- FileSwitch Problem Report ---\n\n");
            bodyBuilder.append("Problem Category: ").append(category).append("\n\n");
            bodyBuilder.append("Problem Description:\n").append(desc).append("\n\n");
            if (!details.isEmpty()) {
                bodyBuilder.append("Additional Details / Affected Formats:\n").append(details).append("\n\n");
            }
            if (includeDiag.isChecked()) {
                bodyBuilder.append("--- Device & System Information ---\n");
                bodyBuilder.append("App: FileSwitch v1.0 (Build 1)\n");
                bodyBuilder.append("Manufacturer: ").append(Build.MANUFACTURER).append("\n");
                bodyBuilder.append("Model: ").append(Build.MODEL).append("\n");
                bodyBuilder.append("Android Version: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                bodyBuilder.append("Locale: ").append(Locale.getDefault().toString()).append("\n");
            }
            bodyBuilder.append("\nSubmitted via FileSwitch In-App Problem Report");

            sendSupportEmail("FileSwitch Problem Report: " + category, bodyBuilder.toString());
        }), layoutTop(16));

        LinearLayout helpNote = column();
        pad(helpNote, 16, 12, 16, 12);
        helpNote.setBackground(shape(surface, 14, line));
        helpNote.addView(text("Direct Developer Support", 13, true, ink));
        TextView noteDesc = text("You can also email developer Sankalp Verma directly at vermasankalp345@gmail.com with screenshots or sample files.", 12, false, muted);
        noteDesc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams nlp = lp(-1, -2);
        nlp.topMargin = dp(4);
        helpNote.addView(noteDesc, nlp);
        body.addView(helpNote, layoutTop(12));
    }

    private void renderSettingsAbout() {
        heading("About FileSwitch", "Offline, private, universal file converter for Android.");

        LinearLayout logoCard = column();
        logoCard.setGravity(Gravity.CENTER_HORIZONTAL);
        pad(logoCard, 20, 20, 20, 20);
        logoCard.setBackground(shape(surface, 16, line));

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.fileswitch_icon);
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logolp = lp(72, 72);
        logolp.gravity = Gravity.CENTER_HORIZONTAL;
        logoCard.addView(logo, logolp);

        TextView title = text("FileSwitch", 22, true, ink);
        title.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = lp(-2, -2);
        tp.topMargin = dp(10);
        logoCard.addView(title, tp);

        TextView ver = text("Version 1.0 (Build 1)", 13, false, muted);
        ver.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = lp(-2, -2);
        vp.topMargin = dp(2);
        logoCard.addView(ver, vp);

        TextView dev = text("Developer: Sankalp Verma", 14, true, ink);
        dev.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams dp = lp(-2, -2);
        dp.topMargin = dp(8);
        logoCard.addView(dev, dp);

        TextView copy = text("© 2026 Sankalp Verma. All rights reserved.", 12, false, muted);
        copy.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cp = lp(-2, -2);
        cp.topMargin = dp(4);
        logoCard.addView(copy, cp);

        body.addView(logoCard, layoutTop(8));

        LinearLayout infoCard = column();
        pad(infoCard, 16, 14, 16, 14);
        infoCard.setBackground(shape(surface, 16, line));
        infoCard.addView(text("About the Application", 15, true, ink));
        TextView desc = text("FileSwitch is a native Android file utility built for seamless, 100% offline file conversion, PDF management, optical character recognition, and document processing without sending your data to any cloud.", 13, false, muted);
        desc.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams dp1 = lp(-1, -2);
        dp1.topMargin = dp(6);
        infoCard.addView(desc, dp1);
        body.addView(infoCard, layoutTop(14));

        body.addView(settingsNavCard("Privacy Policy", "How your data is protected", () -> showPage("Settings_PrivacyPolicy")), layoutTop(12));
        body.addView(settingsNavCard("Terms of Use", "Conditions of use and legal terms", () -> showPage("Settings_Terms")), layoutTop(8));
        body.addView(settingsNavCard("Open Source Licenses", "Third-party components and licenses", () -> showPage("Settings_Licenses")), layoutTop(8));
        body.addView(settingsNavCard("Copyright Information", "Ownership and trademark notice", () -> showPage("Settings_Copyright")), layoutTop(8));
        body.addView(settingsNavCard("Contact & Support", "Email vermasankalp345@gmail.com", () -> sendSupportEmail("FileSwitch Inquiry", "Hi Sankalp,\n\n")), layoutTop(8));
    }

    private void renderSettingsLicenses() {
        heading("Open Source Licenses", "Third-party software libraries used by FileSwitch.");

        String[][] libs = {
                {"PdfBox-Android (v2.0.27.0)", "Apache License 2.0", "com.tom-roush:pdfbox-android\nPDF document manipulation, page splitting, merging, rendering and metadata management.\nCopyright © Tom Roush & The Apache Software Foundation."},
                {"AndroidSVG (v1.4)", "Apache License 2.0", "com.caverock:androidsvg-aar\nVector graphics rendering for SVG document and image conversion.\nCopyright © Paul LeBeau."},
                {"Google ML Kit Text Recognition (v16.0.1)", "Apache License 2.0 / Google APIs Terms", "com.google.mlkit:text-recognition\nOn-device Optical Character Recognition (OCR) for Latin, Devanagari, Chinese, Japanese, and Korean scripts.\nCopyright © Google LLC."},
                {"AndroidX ExifInterface (v1.3.7)", "Apache License 2.0", "androidx.exifinterface:exifinterface\nReading and editing EXIF metadata tags in JPEG, WebP, and PNG images.\nCopyright © The Android Open Source Project."},
                {"Apache POI Android (v3.12)", "Apache License 2.0", "io.github.jiangjm424:poi-android\nReading, parsing and converting Microsoft Office formats (DOCX, XLSX, PPTX, XLS).\nCopyright © The Apache Software Foundation."}
        };

        for (String[] lib : libs) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(lib[0], 15, true, ink));
            TextView lic = text("License: " + lib[1], 12, true, accent);
            LinearLayout.LayoutParams lp1 = lp(-1, -2);
            lp1.topMargin = dp(2);
            card.addView(lic, lp1);
            TextView desc = text(lib[2], 12, false, muted);
            desc.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams lp2 = lp(-1, -2);
            lp2.topMargin = dp(6);
            card.addView(desc, lp2);
            body.addView(card, layoutTop(10));
        }

        LinearLayout apCard = column();
        pad(apCard, 16, 14, 16, 14);
        apCard.setBackground(shape(surface, 16, line));
        apCard.addView(text("Apache License 2.0 Summary", 14, true, ink));
        TextView apText = text("Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use these files except in compliance with the License. You may obtain a copy of the License at\n\nhttp://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software distributed under the License is distributed on an \"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.", 11, false, muted);
        apText.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams alp = lp(-1, -2);
        alp.topMargin = dp(6);
        apCard.addView(apText, alp);
        body.addView(apCard, layoutTop(12));
    }

    private void renderSettingsCopyright() {
        heading("Copyright", "Legal notice, ownership and third-party licensing.");

        body.addView(settingsNavCard("Copyright Notice", "App ownership, original code, UI, branding, and assets", () -> showPage("Settings_Copyright_Notice")), layoutTop(8));
        body.addView(settingsNavCard("View Open Source Licenses", "Inspect third-party library licenses", () -> showPage("Settings_Licenses")), layoutTop(8));
    }

    private void renderCopyrightNotice() {
        heading("Copyright Notice", "Intellectual property ownership and licensing terms.");

        String[][] sections = {
                {"Application Ownership & Original Work",
                        "FileSwitch is developed and owned by Sankalp Verma.\n\nAll original source code, application architecture, interface designs, graphic layouts, custom iconography, and brand identity are the proprietary intellectual property of the developer.\n\n© 2026 Sankalp Verma. All rights reserved."},

                {"Permitted Use & User Ownership",
                        "You are granted a personal, non-exclusive license to use FileSwitch on your compatible Android devices for personal, educational, and commercial file conversion tasks.\n\nYou retain 100% full and exclusive ownership of all source files, converted documents, extracted data, and images processed through FileSwitch."},

                {"Intellectual Property Restrictions",
                        "You may not decompile, reverse-engineer, modify, redistribute, sublicense, or sell the FileSwitch application or any of its proprietary components, source code, or visual assets without prior written authorization from the developer."},

                {"Third-Party Open Source Libraries",
                        "FileSwitch uses reputable open-source software libraries to handle specialized local operations, including PDF processing, vector graphics rendering, optical character recognition, EXIF metadata parsing, and office document decoding.\n\nThese third-party components remain the copyrighted property of their respective authors and the Apache Software Foundation, and are licensed under their respective open-source licenses (such as the Apache License 2.0)."},

                {"Attribution & License Compliance",
                        "FileSwitch respects open-source developers and complies fully with all applicable third-party license requirements and attribution notices. You can review the complete list of libraries and license terms in the Open Source Licenses section."}
        };

        for (int i = 0; i < sections.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(sections[i][0], 15, true, ink));
            TextView desc = text(sections[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(settingsNavCard("View Open Source Licenses", "Inspect third-party library licenses", () -> showPage("Settings_Licenses")), layoutTop(16));
        body.addView(button("Contact Developer", false, () -> sendSupportEmail("FileSwitch Copyright & Legal Inquiry", "Hi Sankalp,\n\n")), layoutTop(8));
    }

    private void renderSettingsStorageInfo() {
        heading("Storage & Output", "Where FileSwitch saves your files and manages storage.");

        body.addView(settingsNavCard("Accessible FileSwitch Folder", "Where completed outputs are saved and how to access them", () -> showPage("Settings_Storage_Folder")), layoutTop(8));
        body.addView(settingsNavCard("Duplicate Filename Safety", "How FileSwitch prevents overwriting existing files", () -> showPage("Settings_Storage_DuplicateSafety")), layoutTop(8));
        body.addView(settingsNavCard("Temporary Cache Management", "How intermediate processing cache is used and cleaned up", () -> showPage("Settings_Storage_CacheManagement")), layoutTop(8));

        long cacheSize = getCacheSize();
        LinearLayout cacheCard = column();
        pad(cacheCard, 16, 14, 16, 14);
        cacheCard.setBackground(shape(surface, 16, line));
        cacheCard.addView(text("Cache Status", 15, true, ink));
        TextView cacheDesc = text("Current temporary conversion cache: " + sizeLabel(cacheSize), 13, false, muted);
        LinearLayout.LayoutParams cdp = lp(-1, -2);
        cdp.topMargin = dp(4);
        cacheCard.addView(cacheDesc, cdp);
        cacheCard.addView(button("Clear Temporary Cache Now", false, () -> {
            dialogBuilder()
                    .setTitle("Clear temporary cache")
                    .setMessage("Delete all intermediate conversion cache files? This will not affect your saved files.")
                    .setPositiveButton("Clear", (d, w) -> {
                        clearCache();
                        showPage("Settings_StorageInfo");
                        toast("Temporary cache cleared");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }), layoutTop(10));
        body.addView(cacheCard, layoutTop(14));
    }

    private void renderStorageFolder() {
        heading("FileSwitch Folder", "Where your completed files are stored and how to find them.");

        String[][] sections = {
                {"Dedicated Public Storage Folder",
                        "Whenever an operation completes—such as a file conversion, PDF modification, image crop, compression, or archive extraction—FileSwitch automatically writes the final output file to a dedicated public folder named 'FileSwitch' in your device's internal storage."},

                {"Exact Storage Directory Locations",
                        "Depending on your Android version and manufacturer settings, the folder is located at:\n\n• Primary Location: Documents/FileSwitch/\n• Fallback Location: Download/FileSwitch/\n\nIf neither public directory is directly writable on your device, FileSwitch automatically falls back to internal app storage."},

                {"How to Access Your Files",
                        "You can easily access, open, move, or share your converted files using any file explorer app (such as Google Files, Samsung My Files, or Xiaomi File Manager):\n\n1. Open your File Manager.\n2. Go to Internal Storage.\n3. Open the 'Documents' folder (or 'Download').\n4. Tap 'FileSwitch' to view all your converted files."},

                {"Automatic Media Gallery Indexing",
                        "When FileSwitch saves an image, photo, or document, it immediately requests Android's MediaScanner service to index the file. This makes newly created pictures, converted photos, and documents show up immediately in your Gallery and document viewing apps."},

                {"Permanent User Storage",
                        "Files in the public FileSwitch folder belong entirely to you. FileSwitch never automatically deletes, prunes, or alters completed files in this directory. Only you can delete or move them."}
        };

        for (int i = 0; i < sections.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(sections[i][0], 15, true, ink));
            TextView desc = text(sections[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("View Conversion History", true, () -> showPage("History")), layoutTop(16));
        body.addView(button("Back to Storage & Output", false, () -> showPage("Settings_StorageInfo")), layoutTop(8));
    }

    private void renderStorageDuplicateSafety() {
        heading("Duplicate Safety", "How FileSwitch protects your existing files from being overwritten.");

        String[][] sections = {
                {"Automatic Conflict Detection",
                        "Before saving any converted or modified file to disk, FileSwitch checks whether a file with the same name already exists in your destination FileSwitch folder."},

                {"Incremental Suffix Numbering",
                        "If a filename collision is detected (for example, if 'Document.pdf' already exists in the folder), FileSwitch automatically appends an incremental number in parentheses before the extension:\n\n• Document.pdf (original)\n• Document (1).pdf (first new conversion)\n• Document (2).pdf (second new conversion)\n• Document (3).pdf (subsequent conversion)"},

                {"Protection for Existing Files",
                        "Because FileSwitch uses incremental suffixing, your existing documents and earlier conversion outputs are never overwritten, truncated, or replaced without your knowledge."},

                {"Custom Filenames & Duplicate Handling",
                        "If you enter a custom output filename (such as 'MeetingNotes'), FileSwitch uses that exact name. If 'MeetingNotes.pdf' already exists in storage, FileSwitch safely writes 'MeetingNotes (1).pdf'."},

                {"Batch File Processing",
                        "When converting multiple files simultaneously, FileSwitch applies clear sequential numbering to prevent batch files from conflicting with one another or existing files in your folder."}
        };

        for (int i = 0; i < sections.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(sections[i][0], 15, true, ink));
            TextView desc = text(sections[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        body.addView(button("Back to Storage & Output", false, () -> showPage("Settings_StorageInfo")), layoutTop(16));
    }

    private void renderStorageCacheManagement() {
        heading("Cache Management", "How intermediate processing files are used and cleaned up.");

        String[][] sections = {
                {"What the Temporary Cache Is",
                        "During conversion operations—such as extracting pages from a PDF, rendering vector graphics, decoding archive files, or resizing high-resolution photos—FileSwitch temporarily holds intermediate working data in a dedicated cache directory."},

                {"Private Sandbox Isolation",
                        "The temporary cache directory is located inside the application's private sandbox storage (`getCacheDir()/fileswitch/`).\n\nThese temporary files are completely isolated from your public storage, are never exposed to other apps, and do not appear in your device's file manager."},

                {"Separate from Completed Outputs",
                        "Temporary cache files are strictly working files. When an operation finishes, the completed result is copied to your public FileSwitch folder, and the intermediate working copy is deleted immediately."},

                {"Automatic 24-Hour Cache Pruning",
                        "Every time FileSwitch launches, it runs a background pruning routine that automatically deletes any temporary working files older than 24 hours to prevent unused data from taking up device storage."},

                {"Manual Cache Clearing",
                        "You can safely clear the temporary cache at any time using the button below. Clearing the cache only removes leftover intermediate working files; it will never delete or affect your completed files saved in the FileSwitch folder."}
        };

        for (int i = 0; i < sections.length; i++) {
            LinearLayout card = column();
            pad(card, 16, 14, 16, 14);
            card.setBackground(shape(surface, 16, line));
            card.addView(text(sections[i][0], 15, true, ink));
            TextView desc = text(sections[i][1], 13, false, muted);
            desc.setLineSpacing(dp(3), 1f);
            LinearLayout.LayoutParams p = lp(-1, -2);
            p.topMargin = dp(6);
            card.addView(desc, p);
            body.addView(card, layoutTop(i == 0 ? 8 : 10));
        }

        long cacheSize = getCacheSize();
        LinearLayout actionCard = column();
        pad(actionCard, 16, 14, 16, 14);
        actionCard.setBackground(shape(surface, 16, line));
        actionCard.addView(text("Cache Status", 15, true, ink));
        TextView currentSize = text("Current cache usage: " + sizeLabel(cacheSize), 13, false, muted);
        LinearLayout.LayoutParams csp = lp(-1, -2);
        csp.topMargin = dp(4);
        actionCard.addView(currentSize, csp);
        actionCard.addView(button("Clear Temporary Cache Now", true, () -> {
            dialogBuilder()
                    .setTitle("Clear temporary cache")
                    .setMessage("Delete all intermediate conversion cache files? This will not affect your saved files.")
                    .setPositiveButton("Clear", (d, w) -> {
                        clearCache();
                        showPage("Settings_Storage_CacheManagement");
                        toast("Temporary cache cleared");
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }), layoutTop(12));
        body.addView(actionCard, layoutTop(14));

        body.addView(button("Back to Storage & Output", false, () -> showPage("Settings_StorageInfo")), layoutTop(10));
    }

    private void showLicensesDialog() {
        showPage("Settings_Licenses");
    }

    private long getCacheSize() {
        File dir = new File(getCacheDir(), "fileswitch");
        if (!dir.exists()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        long total = 0;
        for (File f : files) total += f.length();
        return total;
    }

    private void clearCache() {
        File dir = new File(getCacheDir(), "fileswitch");
        if (!dir.exists()) return;
        File[] fs = dir.listFiles();
        if (fs != null) {
            for (File f : fs) f.delete();
        }
    }

    private void showNavigationMenu(View anchor) {
        PopupWindow popup = new PopupWindow(this);
        LinearLayout menuLayout = column();
        pad(menuLayout, 8, 8, 8, 8);
        menuLayout.setBackground(shape(surface, 16, line));
        if (Build.VERSION.SDK_INT >= 21) {
            menuLayout.setElevation(dp(12));
        }

        String[] options = {"Convert", "Tools", "History", "Settings"};
        for (String option : options) {
            boolean selected = option.equals(page);
            TextView item = text(selected ? "●  " + option : option, 14, selected, selected ? accent : ink);
            pad(item, 16, 12, 16, 12);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setBackground(shape(selected ? (isDark() ? Color.parseColor("#28313D") : Color.parseColor("#EBF1FA")) : surface, 10, 0));
            item.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(.96f).scaleY(.96f).setDuration(90).start();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                return false;
            });
            item.setOnClickListener(v -> {
                popup.dismiss();
                if (isClickDebounced()) return;
                if (!getPreferences(0).getBoolean("consent_accepted", false) || !getPreferences(0).getBoolean("storage_consent_accepted", false)) {
                    showPage("Consent");
                    return;
                }
                if (busy && !"Convert".equals(option)) {
                    toast("Conversion is currently in progress");
                    return;
                }
                if (toolProcessing && !"Tools".equals(option) && !"ToolPage".equals(option)) {
                    toast("Operation is currently in progress");
                    return;
                }
                if (!option.equals(page)) {
                    showPage(option);
                }
            });
            LinearLayout.LayoutParams ip = lp(150, -2);
            menuLayout.addView(item, ip);
        }

        popup.setContentView(menuLayout);
        popup.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        popup.setFocusable(true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        if (Build.VERSION.SDK_INT >= 21) {
            popup.setElevation(dp(12));
        }
        menuLayout.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int popupWidth = menuLayout.getMeasuredWidth();
        int anchorWidth = anchor.getWidth();
        popup.showAsDropDown(anchor, -(popupWidth - anchorWidth), dp(8));
    }

    private void pruneCache() {
        File[] fs = new File(getCacheDir(), "fileswitch").listFiles();
        if (fs == null) return;
        long cutoff = System.currentTimeMillis() - 86400000L;
        for (File f : fs) if (f.lastModified() < cutoff) f.delete();
    }

    private void toast(String message) {
        runOnUiThread(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    private void handleBackNavigation() {
        if ("Consent".equals(page)) {
            finishAffinity();
        } else if (page.startsWith("Settings_Help_")) {
            showPage("Settings_HelpSupport");
        } else if (page.startsWith("Settings_Copyright_")) {
            showPage("Settings_Copyright");
        } else if (page.startsWith("Settings_Storage_")) {
            showPage("Settings_StorageInfo");
        } else if ("ToolPage".equals(page)) {
            if (toolProcessing) {
                cancelBatch = true;
                toolProcessing = false;
                toolStatusText = "";
            }
            toolSources.clear();
            toolResultFiles.clear();
            activeToolId = null;
            showPage("Tools");
        } else if (page.startsWith("Settings_") || "Help".equals(page)) {
            if (!getPreferences(0).getBoolean("consent_accepted", false)) {
                showSplashOrConsent();
            } else {
                showPage("Settings");
            }
        } else if (!"Convert".equals(page)) {
            showPage("Convert");
        } else {
            // On Home/Convert screen
            if (busy) {
                cancelBatch = true;
                busy = false;
                if (convertButton != null) convertButton.setText("Convert files");
                if (convertAnimView != null) {
                    convertAnimView.stopImmediate();
                    convertAnimView.setVisibility(View.GONE);
                }
                if (progressText != null) progressText.setText("Conversion cancelled");
                renderFileRows();
                renderResults();
                return;
            }
            showExitConfirmationDialog();
        }
    }

    private void showExitConfirmationDialog() {
        if (isFinishing()) return;
        Dialog dialog = new Dialog(this);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);

        LinearLayout card = column();
        pad(card, 20, 20, 20, 20);
        card.setBackground(shape(surface, 20, line));
        if (Build.VERSION.SDK_INT >= 21) {
            card.setElevation(dp(12));
        }

        LinearLayout titleRow = row();
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView iconView = text("🚪", 18, true, accent);
        TextView titleView = text("Exit FileSwitch", 18, true, ink);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(8);
        titleRow.addView(iconView);
        titleRow.addView(titleView, tp);
        card.addView(titleRow);

        TextView msgView = text("Are you sure you want to exit?", 14, false, muted);
        LinearLayout.LayoutParams mp = lp(-1, -2);
        mp.topMargin = dp(10);
        mp.bottomMargin = dp(20);
        card.addView(msgView, mp);

        LinearLayout btnRow = row();
        TextView cancelBtn = button("Cancel", false, dialog::dismiss);
        cancelBtn.setMinHeight(dp(44));
        cancelBtn.setTextSize(14);
        pad(cancelBtn, 8, 0, 8, 0);

        TextView exitBtn = button("Exit", true, () -> {
            dialog.dismiss();
            finishAffinity();
        });
        exitBtn.setMinHeight(dp(44));
        exitBtn.setTextSize(14);
        pad(exitBtn, 8, 0, 8, 0);

        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        alp.leftMargin = dp(12);
        btnRow.addView(cancelBtn, dlp);
        btnRow.addView(exitBtn, alp);

        card.addView(btnRow, lp(-1, -2));

        dialog.setContentView(card);
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            android.view.WindowManager.LayoutParams wlp = window.getAttributes();
            wlp.gravity = Gravity.CENTER;
            window.setAttributes(wlp);
            int width = (int) (getResources().getDisplayMetrics().widthPixels * 0.88f);
            window.setLayout(Math.min(width, dp(340)), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.show();
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_MODERATE) {
            uriSizeCache.clear();
            uriNameCache.clear();
            uriTypeCache.clear();
        }
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        uriSizeCache.clear();
        uriNameCache.clear();
        uriTypeCache.clear();
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        if (notifManager != null) notifManager.dismissAll();
        clearInputFiles();
        super.onDestroy();
    }
}
