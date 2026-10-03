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
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.rendering.PDFRenderer;
import com.tom_roush.pdfbox.rendering.ImageType;

import java.io.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {
    private static final int PICK_FILES = 1, SAVE_FILE = 2, PICK_PDF_TOOL = 3, PICK_ZIP = 4, PICK_CREATE_ZIP = 5, PICK_IMAGE_FOR_PDF = 6, REQ_NOTIF = 7, PICK_EXIF_IMAGE = 8, PICK_CONTACT_SHEET = 9, PICK_REPAIR_DOC = 10;
    private static final long MAX_FILE = 100L * 1024 * 1024, MAX_PIXELS = 20_000_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<InputFile> files = new ArrayList<>();
    private ConversionNotificationManager notifManager;
    private LinearLayout root, body, fileRows, resultRows;
    private TextView progressText, convertButton;
    private Spinner formatPicker;
    private Spinner imageOperationPicker;
    private int imageQuality = 90;
    private int outputDpi = 96;
    private String page = "Convert", themeChoice = "System";
    private String preferredTarget;
    private String pendingPdfTool;
    private ArrayList<Uri> pendingPdfImageSources;
    private boolean busy, cancelBatch;
    private boolean retryFailedOnly;
    private boolean splashShown;
    private EditText batchPrefixInput;
    private OutputFile batchArchive;

    private static final class InputFile {
        Uri uri;
        String name, type, state = "Ready", details = "";
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
        PdfTools.init(this);
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
                showPage("Convert");
                addPickedFiles(incoming);
                return;
            }
        }

        if (!splashShown && state == null) {
            showSplash();
        } else {
            showPage("Convert");
        }
    }

    private void showSplash() {
        splashShown = true;
        setPalette();
        boolean dark = "Dark".equals(themeChoice) || ("System".equals(themeChoice) && (getResources().getConfiguration().uiMode & 0x30) == 0x20);
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

        splashRoot.setAlpha(0f);
        splashRoot.animate().alpha(1f).setDuration(300).start();

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            splashRoot.animate().alpha(0f).setDuration(220).withEndAction(() -> showPage("Convert")).start();
        }, 1100);
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
            }
        }
    }

    private int bg, surface, ink, muted, accent, line;

    private boolean isDark() {
        return "Dark".equals(themeChoice) || ("System".equals(themeChoice) && (getResources().getConfiguration().uiMode & 0x30) == 0x20);
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
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
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

    private void showPage(String next) {
        page = next;
        setPalette();
        root = column();
        root.setBackgroundColor(bg);
        setContentView(root);

        LinearLayout top = row();
        pad(top, 20, 16, 20, 14);

        if ("Convert".equals(page)) {
            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.fileswitch_icon);
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
            TextView subTag = text("FILE UTILITY", 10, true, muted);
            subTag.setLetterSpacing(0.12f);
            LinearLayout.LayoutParams stp = lp(-2, -2);
            stp.topMargin = dp(2);
            title.addView(subTag, stp);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
            tp.leftMargin = dp(12);
            top.addView(title, tp);
        } else {
            TextView back = text("←", 22, true, ink);
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("Back");
            back.setBackground(shape(surface, 12, line));
            back.setOnTouchListener((v, event) -> {
                if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                    v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).start();
                else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
                return false;
            });
            back.setOnClickListener(v -> showPage("Convert"));
            top.addView(back, lp(40, 40));

            View spacer = new View(this);
            top.addView(spacer, new LinearLayout.LayoutParams(0, 0, 1));
        }

        TextView menu = text("☰", 20, false, ink);
        menu.setGravity(Gravity.CENTER);
        menu.setContentDescription("Menu");
        menu.setBackground(shape(surface, 12, line));
        menu.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_DOWN)
                v.animate().scaleX(.92f).scaleY(.92f).setDuration(90).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            return false;
        });
        menu.setOnClickListener(this::showNavigationMenu);
        top.addView(menu, lp(40, 40));

        root.addView(top, lp(-1, -2));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        body = column();
        pad(body, 20, 10, 20, 24);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        switch (next) {
            case "Convert":
                renderConvert();
                break;
            case "Tools":
                renderTools();
                break;
            case "History":
                renderHistory();
                break;
            default:
                renderSettings();
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
                v.animate().scaleX(.975f).scaleY(.975f).setDuration(90).start();
            else if (event.getAction() == android.view.MotionEvent.ACTION_UP || event.getAction() == android.view.MotionEvent.ACTION_CANCEL)
                v.animate().scaleX(1f).scaleY(1f).setDuration(180).start();
            return false;
        });
        b.setOnClickListener(v -> action.run());
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
            TextView sub = text(files.size() + " selected", 15, true, ink);
            body.addView(sub);
            LinearLayout actions = row();
            TextView add = button("Add files", false, this::openPicker);
            actions.addView(add, new LinearLayout.LayoutParams(0, dp(48), 1));
            TextView clear = button("Clear", false, () -> {
                if (!busy) {
                    files.clear();
                    batchArchive = null;
                    showPage("Convert");
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

            if (files.size() > 1) {
                batchPrefixInput = new EditText(this);
                batchPrefixInput.setSingleLine(true);
                batchPrefixInput.setHint("Optional name prefix for batch files");
                batchPrefixInput.setTextSize(14);
                batchPrefixInput.setPadding(dp(14), 0, dp(14), 0);
                styleEditText(batchPrefixInput);
                LinearLayout.LayoutParams namep = lp(-1, 50);
                namep.topMargin = dp(8);
                body.addView(batchPrefixInput, namep);
            } else batchPrefixInput = null;

            if (files.stream().allMatch(f -> Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg").contains(f.type))) {
                imageOperationPicker = new Spinner(this);
                String[] edits = {"None", "Resize 25%", "Resize 50%", "Resize 75%", "Crop center 50%", "Crop center 80%", "Rotate 90°", "Flip horizontal", "Flip vertical", "Grayscale"};
                imageOperationPicker.setAdapter(createThemedAdapter(edits));
                LinearLayout.LayoutParams ep = lp(-1, 50);
                ep.topMargin = dp(8);
                body.addView(imageOperationPicker, ep);
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
            return new String[]{"DOCX", "Searchable PDF", "OCR (DOCX)", "OCR (Latin)", "OCR (Devanagari)", "OCR (Chinese)", "OCR (Japanese)", "OCR (Korean)", "PNG", "JPG", "WebP", "TXT", "HTML", "Markdown"};
        }
        if (!files.isEmpty() && files.stream().allMatch(f -> f.type.equals(files.get(0).type))) {
            switch (files.get(0).type) {
                case "docx":
                    return new String[]{"TXT", "HTML", "PDF"};
                case "xlsx":
                    return new String[]{"CSV", "PDF"};
                case "xls":
                    return new String[]{"CSV", "XLSX", "PDF"};
                case "pptx":
                case "odt":
                    return new String[]{"TXT", "HTML", "DOCX", "PDF"};
                case "csv":
                    return new String[]{"XLSX", "PDF"};
                case "txt":
                    return new String[]{"PDF", "DOCX", "HTML"};
                case "md":
                    return new String[]{"HTML", "PDF", "DOCX"};
                case "html":
                    return new String[]{"PDF", "TXT"};
                case "rtf":
                    return new String[]{"DOCX", "PDF", "TXT"};
            }
        }
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add("PNG");
        out.add("JPG");
        out.add("WebP");
        out.add("PDF");
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
        if (files.stream().anyMatch(f -> f.type.equals("pdf"))) out.remove("PDF");
        if (files.size() == 1) {
            String in = files.get(0).type;
            out.remove(in.equals("jpeg") ? "JPG" : in.toUpperCase(Locale.ROOT));
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
            info.addView(text(f.state + "  ·  " + sizeLabel(querySize(f.uri)) + (f.details.isEmpty() ? "" : "  ·  " + f.details), 12, false, muted));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1);
            ip.leftMargin = dp(10);
            r.addView(info, ip);
            if (f.output != null) r.addView(text("✓", 19, true, Color.parseColor("#25845A")));
            TextView remove = text("×", 22, false, muted);
            remove.setGravity(Gravity.CENTER);
            remove.setContentDescription("Remove " + f.name);
            remove.setBackground(shape(bg, 22, 0));
            remove.setOnClickListener(v -> {
                if (busy && f.state.equals("Waiting")) {
                    f.state = "Cancelled";
                    renderFileRows();
                } else if (!busy) {
                    files.remove(f);
                    showPage("Convert");
                }
            });
            LinearLayout.LayoutParams rm = lp(38, 38);
            rm.leftMargin = dp(8);
            r.addView(remove, rm);
            LinearLayout.LayoutParams rlp = lp(-1, -2);
            if (i++ > 0) rlp.topMargin = dp(7);
            fileRows.addView(r, rlp);
        }
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
                r.addView(text(output.name, 14, true, ink));
                if (output.sourceSize > 0) {
                    long outSize = output.file.length();
                    String metric = sizeLabel(output.sourceSize) + "  →  " + sizeLabel(outSize);
                    if (outSize < output.sourceSize)
                        metric += "  ·  " + Math.round((1f - outSize / (float) output.sourceSize) * 100) + "% smaller";
                    r.addView(text(metric, 12, false, muted));
                }
                LinearLayout acts = row();
                TextView save = button("Save", true, () -> saveOutput(output));
                TextView share = button("Share", false, () -> shareOutput(output));
                TextView open = button("Open", false, () -> openOutput(output));
                ArrayList<TextView> btnList = new ArrayList<>(Arrays.asList(save, share, open));
                if ("text/plain".equals(output.mime)) {
                    btnList.add(button("Copy", false, () -> copyTextOutput(output)));
                }
                btnList.add(button("Delete", false, () -> deleteOutput(output)));
                for (TextView b : btnList) {
                    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1);
                    p.leftMargin = dp(3);
                    p.rightMargin = dp(3);
                    acts.addView(b, p);
                }
                LinearLayout.LayoutParams p = lp(-1, -2);
                p.topMargin = dp(10);
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
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int n = c.getColumnIndex(OpenableColumns.SIZE);
                if (n >= 0 && !c.isNull(n)) return c.getLong(n);
            }
        } catch (Exception ignored) {}
        return 0;
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
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "application/pdf", "text/*", "application/rtf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "application/vnd.ms-excel", "application/vnd.oasis.opendocument.text"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, PICK_FILES);
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code == SAVE_FILE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                OutputFile f = pendingSave;
                pendingSave = null;
                worker.execute(() -> copyTo(f, data.getData()));
            } else pendingSave = null;
            return;
        }
        if (code == PICK_ZIP && result == RESULT_OK && data != null && data.getData() != null) {
            extractZip(data.getData());
            return;
        }
        if (code == PICK_CREATE_ZIP && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) createZipFromUris(picked);
            return;
        }
        if (code == PICK_IMAGE_FOR_PDF && result == RESULT_OK && data != null && data.getData() != null) {
            requestPdfImagePage(data.getData());
            return;
        }
        if (code == PICK_PDF_TOOL && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) requestPdfOptions(pendingPdfTool, picked);
            return;
        }
        if (code == PICK_EXIF_IMAGE && result == RESULT_OK && data != null && data.getData() != null) {
            showExifEditDialog(data.getData());
            return;
        }
        if (code == PICK_CONTACT_SHEET && result == RESULT_OK && data != null) {
            ArrayList<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null)
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    picked.add(data.getClipData().getItemAt(i).getUri());
            else if (data.getData() != null) picked.add(data.getData());
            if (!picked.isEmpty()) showContactSheetDialog(picked);
            return;
        }
        if (code == PICK_REPAIR_DOC && result == RESULT_OK && data != null && data.getData() != null) {
            repairDocument(data.getData());
            return;
        }
        if (code != PICK_FILES || result != RESULT_OK || data == null) return;
        ArrayList<Uri> picked = new ArrayList<>();
        if (data.getClipData() != null)
            for (int i = 0; i < data.getClipData().getItemCount(); i++)
                picked.add(data.getClipData().getItemAt(i).getUri());
        else if (data.getData() != null) picked.add(data.getData());
        addPickedFiles(picked);
    }

    private void addPickedFiles(List<Uri> picked) {
        for (Uri uri : picked) {
            String name = displayName(uri), type = detectType(uri, name);
            if (!Arrays.asList("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif", "svg", "pdf", "docx", "xlsx", "pptx", "odt", "xls", "csv", "txt", "md", "html", "rtf").contains(type)) {
                toast(name + " is not a supported format.");
                continue;
            }
            if (querySize(uri) > MAX_FILE) {
                toast(name + " is over the 100 MB limit.");
                continue;
            }
            if (files.stream().anyMatch(f -> f.uri.equals(uri))) continue;
            InputFile file = new InputFile(uri, name, type);
            files.add(file);
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
            zip.delete();
            throw error;
        }
        return new OutputFile(zip, "FileSwitch-results.zip", "application/zip", "zip");
    }

    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        return "file";
    }

    private String detectType(Uri uri) {
        return detectType(uri, displayName(uri));
    }

    private String detectType(Uri uri, String name) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            byte[] b = new byte[512];
            int n = in.read(b);
            if (n >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return "png";
            if (n >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "jpg";
            if (n >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P')
                return "webp";
            if (n >= 2 && b[0] == 'B' && b[1] == 'M') return "bmp";
            if (n >= 4 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "gif";
            if (n >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "pdf";
            String sample = new String(b, 0, Math.max(0, n), java.nio.charset.StandardCharsets.UTF_8).trim();
            if (sample.matches("(?is)(<\\?xml[^>]*>\\s*)?<svg[\\s>].*")) return "svg";
            if (n >= 12 && new String(b, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")) {
                String brand = new String(b, 8, Math.min(n - 8, 16), java.nio.charset.StandardCharsets.US_ASCII).toLowerCase(Locale.ROOT);
                if (brand.contains("heic") || brand.contains("heif") || brand.contains("heix") || brand.contains("hevc") || brand.contains("heim") || brand.contains("heis") || brand.contains("mif1") || brand.contains("msf1")) return "heic";
            }
            if (n >= 4 && b[0] == 'P' && b[1] == 'K' && (b[2] == 3 || b[2] == 5 || b[2] == 7) && (b[3] == 4 || b[3] == 6 || b[3] == 8)) {
                String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
                if (Arrays.asList("docx", "xlsx", "pptx", "odt").contains(ext)) return ext;
                return "zip";
            }
        } catch (Exception ignored) {}
        String ext = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        if (Arrays.asList("docx", "xlsx", "pptx", "odt", "xls", "zip").contains(ext)) return ext;
        return Arrays.asList("csv", "txt", "md", "html", "rtf", "heic", "heif", "svg", "jpg", "jpeg", "png", "webp", "bmp", "gif", "pdf").contains(ext) ? (ext.equals("jpeg") ? "jpg" : ext) : "";
    }

    private void startConversion() {
        if (busy || files.isEmpty() || formatPicker.getSelectedItem() == null) return;
        if (getCacheDir().getUsableSpace() < 15L * 1024 * 1024) {
            toast("Insufficient storage space on device");
            return;
        }
        boolean onlyFailed = retryFailedOnly;
        retryFailedOnly = false;
        String selected = ((String) formatPicker.getSelectedItem()).toLowerCase(Locale.ROOT);
        final String target = selected.equals("markdown") ? "md"
                : selected.equals("ocr (latin)") ? "ocr-latin"
                : selected.equals("ocr (devanagari)") ? "ocr-devanagari"
                : selected.equals("ocr (chinese)") ? "ocr-chinese"
                : selected.equals("ocr (japanese)") ? "ocr-japanese"
                : selected.equals("ocr (korean)") ? "ocr-korean"
                : selected.equals("ocr (docx)") ? "ocr-docx"
                : selected.equals("searchable pdf") ? "searchable-pdf"
                : selected.equals("gif (keep animation)") ? "gif"
                : selected;
        final String edit = imageOperationPicker == null ? "None" : (String) imageOperationPicker.getSelectedItem();
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
        convertButton.setText("Cancel conversion");
        final String prefix = batchPrefixInput == null ? "" : safeName(batchPrefixInput.getText().toString().trim());
        int total = queue.size();

        if (target.equals("pdf") && queue.size() > 1 && queue.stream().allMatch(f -> !f.type.equals("pdf"))) {
            worker.execute(() -> convertImagesToPdf(queue, edit, prefix));
            return;
        }

        worker.execute(() -> {
            int completed = 0;
            int successful = 0;
            OutputFile firstSuccessOutput = null;
            for (InputFile f : queue) {
                if (cancelBatch) {
                    f.state = "Cancelled";
                    saveHistory(f.name, f.type, target, 0, "cancelled", null, null);
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
                    f.output = convertFile(f, target, edit, quality);
                    f.output.sourceSize = querySize(f.uri);
                    for (OutputFile out : f.extraOutputs) out.sourceSize = f.output.sourceSize;
                    if (!prefix.isEmpty()) renameBatchOutputs(f, prefix, current);
                    f.state = "Ready";
                    successful++;
                    if (firstSuccessOutput == null) firstSuccessOutput = f.output;
                    saveHistory(f.name, f.type, target, f.output.file.length(), "complete", f.output.file.getAbsolutePath(), f.output.mime);
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "Could not convert" : e.getMessage();
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
            } else if (finalSuccess > 0) {
                notifManager.showComplete(finalSuccess);
            } else {
                notifManager.showFailed("All files failed to convert");
            }
            runOnUiThread(() -> {
                busy = false;
                convertButton.setText("Convert files");
                progressText.setText(cancelBatch ? "Conversion cancelled" : "Finished · Save, share or open your files");
                renderFileRows();
                renderResults();
                if (getPreferences(0).getBoolean("auto_open_result", false) && autoOpenTarget != null && !cancelBatch) {
                    openOutput(autoOpenTarget);
                }
            });
        });
    }

    private void convertImagesToPdf(ArrayList<InputFile> queue, String edit, String prefix) {
        ArrayList<Bitmap> pages = new ArrayList<>();
        File out = null;
        try {
            long sourceSize = 0;
            for (int i = 0; i < queue.size(); i++) {
                if (cancelBatch) throw new IOException("Conversion cancelled");
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
            out = new File(dir, UUID.randomUUID() + "-images.pdf");
            writePdf(pages, out);
            String name = prefix.isEmpty() ? "images.pdf" : prefix + ".pdf";
            OutputFile result = new OutputFile(out, name, "application/pdf", "pdf");
            result.sourceSize = sourceSize;
            InputFile first = queue.get(0);
            first.output = result;
            first.state = "Ready";
            for (int i = 1; i < queue.size(); i++) {
                queue.get(i).state = "Included in combined PDF";
            }
            for (InputFile input : queue) saveHistory(input.name, input.type, "pdf", out.length(), "complete", out.getAbsolutePath(), "application/pdf");
            notifManager.showComplete(1);
        } catch (Exception e) {
            if (out != null) out.delete();
            String message = e.getMessage() == null ? "Could not create PDF" : e.getMessage();
            for (InputFile input : queue) {
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
                progressText.setText(cancelBatch ? "Conversion cancelled" : "Finished · Save, share or open your files");
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
        for (OutputFile output : outputs) {
            String ext = output.name.contains(".") ? output.name.substring(output.name.lastIndexOf('.')) : "";
            String name = prefix + "_" + number + (part++ == 0 ? "" : "_" + part) + ext;
            File renamed = new File(output.file.getParentFile(), UUID.randomUUID() + "-" + name);
            if (!output.file.renameTo(renamed)) throw new IOException("Could not rename a converted file");
            output.file = renamed;
            output.name = name;
        }
    }

    private OutputFile convertFile(InputFile input, String target, String edit, int quality) throws Exception {
        if (target.equals("searchable-pdf")) {
            File dir = new File(getCacheDir(), "fileswitch");
            File out = OcrTools.searchablePdf(getContentResolver(), input.uri, input.type, "latin", dir);
            String base = input.name.replaceFirst("\\.[^.]*$", "");
            return new OutputFile(out, base + "-searchable.pdf", "application/pdf", "pdf");
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
                String base = input.name.replaceFirst("\\.[^.]*$", "");
                File out = OfficeTools.writeDocx(recognized, dir, base + ".docx");
                return new OutputFile(out, base + ".docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            }
            String recognized = input.type.equals("pdf") ? OcrTools.pdfText(getContentResolver(), input.uri, lang) : OcrTools.imageText(getContentResolver(), input.uri, input.type, lang);
            String base = input.name.replaceFirst("\\.[^.]*$", "");
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            File out = new File(dir, UUID.randomUUID() + "-" + safeName(base + ".txt"));
            try (FileOutputStream stream = new FileOutputStream(out)) {
                stream.write(recognized.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            return new OutputFile(out, base + ".txt", "text/plain", "txt");
        }
        if (target.equals("gif") && input.type.equals("gif")) {
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            String base = input.name.replaceFirst("\\.[^.]*$", "");
            File out = new File(dir, UUID.randomUUID() + "-" + safeName(base + ".gif"));
            try (InputStream source = getContentResolver().openInputStream(input.uri); OutputStream destination = new FileOutputStream(out)) {
                if (source == null) throw new IOException("GIF could not be opened");
                byte[] buffer = new byte[32768];
                int n;
                while ((n = source.read(buffer)) > 0) destination.write(buffer, 0, n);
            }
            return new OutputFile(out, base + ".gif", "image/gif", "gif");
        }
        if (input.type.equals("pdf")) {
            File dir = new File(getCacheDir(), "fileswitch");
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
            ArrayList<PdfTools.Result> converted = PdfTools.convert(getContentResolver(), input.uri, input.name, target, outputDpi, dir);
            if (converted.isEmpty()) throw new IOException("This PDF has no pages");
            for (int i = 1; i < converted.size(); i++) {
                PdfTools.Result result = converted.get(i);
                input.extraOutputs.add(new OutputFile(result.file, result.name, result.mime, target));
            }
            PdfTools.Result first = converted.get(0);
            return new OutputFile(first.file, first.name, first.mime, target);
        }
        if (Arrays.asList("docx", "xlsx", "xls", "pptx", "odt", "csv", "txt", "md", "html", "rtf").contains(input.type)) {
            File dir = new File(getCacheDir(), "fileswitch");
            OfficeTools.Result result = OfficeTools.convert(getContentResolver(), input.uri, input.name, input.type, target, dir);
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
        String base = input.name.replaceFirst("\\.[^.]*$", "");
        if (base.isEmpty()) base = "converted-file";
        String ext = target.equals("jpg") ? "jpg" : target;
        File dir = new File(getCacheDir(), "fileswitch");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare output");
        File out = new File(dir, UUID.randomUUID() + "-" + safeName(base + "." + ext));
        try {
            if (target.equals("pdf")) writePdf(bitmap, out);
            else {
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
        return new OutputFile(out, base + "." + ext, mime, target);
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

    private String safeName(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("^\\.+", "");
    }

    private OutputFile pendingSave;

    private void saveOutput(OutputFile f) {
        EditText nameInput = new EditText(this);
        nameInput.setSingleLine(true);
        nameInput.setText(f.name);
        nameInput.selectAll();
        styleEditText(nameInput);
        nameInput.setPadding(dp(14), dp(12), dp(14), dp(12));
        dialogBuilder()
                .setTitle("Save as")
                .setView(nameInput)
                .setPositiveButton("Save", (d, w) -> {
                    String chosen = nameInput.getText().toString().trim();
                    if (chosen.isEmpty()) chosen = f.name;
                    pendingSave = f;
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(f.mime);
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
            c.addView(button("Create ZIP from files", true, this::openCreateZipPicker), layoutTop(10));
            c.addView(button("Extract Archive (ZIP, TAR, GZ, TGZ)", false, this::openZipPicker), layoutTop(8));
        } else if (target == null) {
            c.addView(button("Choose images to convert", true, () -> {
                preferredTarget = null;
                showPage("Convert");
                openPicker();
            }), layoutTop(10));
            c.addView(button("Edit Image EXIF Metadata", false, this::openExifEditorPicker), layoutTop(8));
            c.addView(button("Create Contact Sheet", false, this::openContactSheetPicker), layoutTop(8));
        } else if ("document".equals(target)) {
            c.addView(button("Choose Document", true, () -> {
                preferredTarget = target;
                showPage("Convert");
                openPicker();
            }), layoutTop(10));
            c.addView(button("Repair Damaged Document", false, this::openRepairDocPicker), layoutTop(8));
        } else {
            String label = "Choose PDF or images";
            c.addView(button(label, true, () -> {
                preferredTarget = target;
                showPage("Convert");
                openPicker();
            }), layoutTop(14));
        }
        card(parent, c);
    }

    private void addPdfOperationButton(LinearLayout parent, String title, String operation) {
        TextView b = button(title, false, () -> openPdfOperation(operation));
        LinearLayout.LayoutParams p = lp(-1, 48);
        p.topMargin = dp(6);
        parent.addView(b, p);
    }

    private void openPdfOperation(String operation) {
        pendingPdfTool = operation;
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("application/pdf");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, operation.equals("merge"));
        try {
            startActivityForResult(picker, PICK_PDF_TOOL);
        } catch (ActivityNotFoundException e) {
            toast("No PDF picker is available");
        }
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
        Dialog dialog = new Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        LinearLayout rootDlg = column();
        rootDlg.setBackground(shape(surface, 20, line));
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
        closeBtn.setBackground(shape(bg, 14, 0));
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        header.addView(closeBtn, lp(36, 36));
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

        dialog.setContentView(rootDlg);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            int w = getResources().getDisplayMetrics().widthPixels - dp(32);
            int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.88f);
            dialog.getWindow().setLayout(w, h);
        }
        return dialog;
    }

    private void requestPdfOptions(String operation, ArrayList<Uri> sources) {
        if (operation.equals("resize")) {
            dialogBuilder().setTitle("Page size and orientation").setItems(new String[]{"A4 portrait", "A4 landscape", "Letter portrait", "Letter landscape"}, (d, w) -> processPdf(operation, sources, w, null)).show();
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
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualPageSelectionDialog(operation, sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("reorder")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualReorderDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("rotate")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualRotateDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("crop")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualCropDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("stamp")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualStampDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("annotate")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualAnnotateDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("fillForm")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualFillFormDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
                }
            });
            return;
        }
        if (operation.equals("sign")) {
            worker.execute(() -> {
                try {
                    int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                    runOnUiThread(() -> showVisualSignDialog(sources, count));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("Could not read PDF page count"));
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
        final float[] cropMargin = {18f};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(prevPage, lp(36, 36));

        TextView pageIndicator = text("Preview: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(nextPage, lp(36, 36));

        content.addView(pagerRow, lp(-1, 36));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, 0));
        LinearLayout.LayoutParams fp = lp(-1, 240);
        fp.topMargin = dp(10);
        content.addView(previewFrame, fp);

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        View cropOverlay = new View(this);
        GradientDrawable cropBorder = new GradientDrawable();
        cropBorder.setColor(Color.argb(30, 24, 87, 213));
        cropBorder.setStroke(dp(2), accent);
        cropOverlay.setBackground(cropBorder);
        FrameLayout.LayoutParams cop = new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        cop.setMargins(dp(18), dp(18), dp(18), dp(18));
        previewFrame.addView(cropOverlay, cop);

        TextView marginLabel = text("Crop margin: 18 pt (~0.25 in)", 13, true, ink);
        LinearLayout.LayoutParams mlp = lp(-1, -2);
        mlp.topMargin = dp(14);
        content.addView(marginLabel, mlp);

        LinearLayout presets = row();
        float[] marginValues = {6f, 12f, 18f, 24f, 36f, 48f};
        String[] marginTitles = {"6 pt", "12 pt", "18 pt", "24 pt", "36 pt", "48 pt"};
        for (int i = 0; i < marginValues.length; i++) {
            final float mVal = marginValues[i];
            TextView mb = button(marginTitles[i], mVal == cropMargin[0], null);
            mb.setOnClickListener(v -> {
                cropMargin[0] = mVal;
                marginLabel.setText(String.format(Locale.getDefault(), "Crop margin: %.0f pt (~%.2f in)", mVal, mVal / 72.0f));
                int pad = Math.max(4, Math.round(mVal * 1.2f));
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) cropOverlay.getLayoutParams();
                p.setMargins(dp(pad), dp(pad), dp(pad), dp(pad));
                cropOverlay.setLayoutParams(p);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1);
            p.leftMargin = dp(2); p.rightMargin = dp(2);
            presets.addView(mb, p);
        }
        LinearLayout.LayoutParams prp = lp(-1, 34);
        prp.topMargin = dp(8);
        content.addView(presets, prp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Apply Crop to All Pages", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Crop Page Margins",
            "Visually preview and adjust margin boundary",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable loadPage = () -> {
            pageIndicator.setText("Preview: Page " + (currentPage[0] + 1) + " of " + totalPages);
            loadPdfPageThumbnail(sourceUri, currentPage[0], 400, bmp -> {
                if (bmp != null) pageImage.setImageBitmap(bmp);
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
            processPdf("crop", sources, 0, String.valueOf(cropMargin[0]));
        });

        loadPage.run();
        dialog.show();
    }

    private void showVisualAddImageDialog(ArrayList<Uri> sources, int totalPages, Uri imageUri) {
        Uri sourceUri = sources.get(0);
        final int[] targetPage = {0};
        final int[] position = {0};

        LinearLayout content = column();

        LinearLayout pagerRow = row();
        TextView prevPage = text("◀", 16, true, ink);
        prevPage.setGravity(Gravity.CENTER);
        prevPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(prevPage, lp(36, 36));

        TextView pageIndicator = text("Insert onto: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(nextPage, lp(36, 36));

        content.addView(pagerRow, lp(-1, 36));

        FrameLayout previewFrame = new FrameLayout(this);
        previewFrame.setBackground(shape(bg, 14, 0));
        LinearLayout.LayoutParams fp = lp(-1, 240);
        fp.topMargin = dp(10);
        content.addView(previewFrame, fp);

        ImageView pageImage = new ImageView(this);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        previewFrame.addView(pageImage, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));

        ImageView overlayImage = new ImageView(this);
        overlayImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        try (InputStream in = getContentResolver().openInputStream(imageUri)) {
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp != null) overlayImage.setImageBitmap(bmp);
        } catch (Exception ignored) {}
        FrameLayout.LayoutParams oip = new FrameLayout.LayoutParams(dp(70), dp(70), Gravity.CENTER);
        previewFrame.addView(overlayImage, oip);

        TextView posLabel = text("Placement position on page:", 13, true, ink);
        LinearLayout.LayoutParams plp = lp(-1, -2);
        plp.topMargin = dp(14);
        content.addView(posLabel, plp);

        LinearLayout posRow = row();
        String[] posNames = {"Center", "Top", "Bottom", "Top-Right", "Bottom-Right"};
        int[] gravities = {Gravity.CENTER, Gravity.TOP | Gravity.CENTER_HORIZONTAL, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, Gravity.TOP | Gravity.END, Gravity.BOTTOM | Gravity.END};
        for (int i = 0; i < posNames.length; i++) {
            final int posIdx = i;
            TextView pb = button(posNames[i], i == 0, null);
            pb.setOnClickListener(v -> {
                position[0] = posIdx;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) overlayImage.getLayoutParams();
                lp.gravity = gravities[posIdx];
                int m = dp(16);
                lp.setMargins(m, m, m, m);
                overlayImage.setLayoutParams(lp);
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(34), 1);
            p.leftMargin = dp(2); p.rightMargin = dp(2);
            posRow.addView(pb, p);
        }
        LinearLayout.LayoutParams prp = lp(-1, 34);
        prp.topMargin = dp(8);
        content.addView(posRow, prp);

        LinearLayout bottomRow = row();
        TextView cancelBtn = button("Cancel", false, null);
        TextView confirmBtn = button("Insert onto Page 1", true, null);
        bottomRow.addView(cancelBtn, new LinearLayout.LayoutParams(0, dp(50), 1));
        LinearLayout.LayoutParams confParams = new LinearLayout.LayoutParams(0, dp(50), 1);
        confParams.leftMargin = dp(8);
        bottomRow.addView(confirmBtn, confParams);

        Dialog dialog = createVisualDialog(
            "Insert Image into PDF",
            "Visually preview image placement",
            content,
            bottomRow
        );
        cancelBtn.setOnClickListener(v -> dialog.dismiss());

        Runnable loadPage = () -> {
            pageIndicator.setText("Insert onto: Page " + (targetPage[0] + 1) + " of " + totalPages);
            confirmBtn.setText("Insert onto Page " + (targetPage[0] + 1));
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
            ArrayList<Uri> finalSources = new ArrayList<>(sources);
            if (!finalSources.contains(imageUri)) finalSources.add(imageUri);
            processPdf("addImage", finalSources, position[0], String.valueOf(targetPage[0] + 1));
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
        prevPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(prevPage, lp(36, 36));

        TextView pageIndicator = text("Preview on: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(nextPage, lp(36, 36));

        content.addView(pagerRow, lp(-1, 36));

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
        prevPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(prevPage, lp(36, 36));

        TextView pageIndicator = text("Preview: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(nextPage, lp(36, 36));

        content.addView(pagerRow, lp(-1, 36));

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
        prevPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(prevPage, lp(36, 36));

        TextView pageIndicator = text("Preview: Page 1 of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(nextPage, lp(36, 36));

        content.addView(pagerRow, lp(-1, 36));

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
        prevPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(prevPage, lp(36, 36));

        TextView pageIndicator = text("Sign on: Page " + (targetPage[0] + 1) + " of " + totalPages, 13, true, ink);
        pageIndicator.setGravity(Gravity.CENTER);
        pagerRow.addView(pageIndicator, new LinearLayout.LayoutParams(0, -2, 1));

        TextView nextPage = text("▶", 16, true, ink);
        nextPage.setGravity(Gravity.CENTER);
        nextPage.setBackground(shape(bg, 10, 0));
        pagerRow.addView(nextPage, lp(36, 36));

        content.addView(pagerRow, lp(-1, 36));

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
        ArrayList<Uri> sources = new ArrayList<>(pendingPdfImageSources);
        pendingPdfImageSources = null;
        worker.execute(() -> {
            try {
                int count = PdfOperations.pageCount(getContentResolver(), sources.get(0));
                runOnUiThread(() -> showVisualAddImageDialog(sources, count, image));
            } catch (Exception e) {
                runOnUiThread(() -> toast("Could not read PDF"));
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
                    runOnUiThread(() -> toast("Could not read PDF details"));
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
            signer.setHint("Signer name (e.g. John Doe)");
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
                processPdf(operation, sources, pg, name.isEmpty() ? "Verified User" : name);
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
            dialogBuilder().setTitle("Compression level").setItems(new String[]{"Low", "Balanced", "High"}, (d, w) -> processPdf(operation, sources, w, null)).show();
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
                            OutputFile output = new OutputFile(dest, dest.getName().replaceFirst("^[^-]+-", ""), "image/jpeg", "jpg");
                            runOnUiThread(() -> {
                                files.clear();
                                InputFile item = new InputFile(Uri.fromFile(dest), output.name, "jpg");
                                item.output = output;
                                item.state = "Ready";
                                files.add(item);
                                showPage("Convert");
                                progressText.setText("EXIF metadata updated");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("Failed to update EXIF: " + e.getMessage()));
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
                    int cols = w == 0 ? 2 : w == 1 ? 3 : 4;
                    int rows = (int) Math.ceil((double) images.size() / cols);
                    worker.execute(() -> {
                        try {
                            File dir = new File(getCacheDir(), "fileswitch");
                            File sheet = ImageTools.generateContactSheet(getContentResolver(), images, cols, rows, dir);
                            OutputFile out = new OutputFile(sheet, sheet.getName().replaceFirst("^[^-]+-", ""), "image/png", "png");
                            runOnUiThread(() -> {
                                files.clear();
                                InputFile item = new InputFile(Uri.fromFile(sheet), out.name, "png");
                                item.output = out;
                                item.state = "Ready";
                                files.add(item);
                                showPage("Convert");
                                progressText.setText("Contact sheet created");
                            });
                        } catch (Exception e) {
                            runOnUiThread(() -> toast("Failed to create contact sheet: " + e.getMessage()));
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
        worker.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "fileswitch");
                String name = displayName(uri);
                String type = detectType(uri, name);
                OfficeTools.Result res = OfficeTools.repairDocument(getContentResolver(), uri, name, type, dir);
                OutputFile out = new OutputFile(res.file, res.name, res.mime, "docx");
                runOnUiThread(() -> {
                    files.clear();
                    InputFile item = new InputFile(Uri.fromFile(res.file), out.name, "docx");
                    item.output = out;
                    item.state = "Ready";
                    files.add(item);
                    showPage("Convert");
                    progressText.setText("Document text salvaged & repaired");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(e.getMessage() == null ? "Document could not be repaired" : e.getMessage()));
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

    private void createZipFromUris(ArrayList<Uri> sources) {
        worker.execute(() -> {
            File zip = null;
            try {
                File dir = new File(getCacheDir(), "fileswitch");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare ZIP");
                zip = new File(dir, UUID.randomUUID() + "-FileSwitch-files.zip");
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
                OutputFile result = new OutputFile(archive, "FileSwitch-files.zip", "application/zip", "zip");
                runOnUiThread(() -> {
                    files.clear();
                    InputFile item = new InputFile(Uri.fromFile(result.file), result.name, "zip");
                    item.output = result;
                    item.state = "Ready";
                    files.add(item);
                    batchArchive = null;
                    showPage("Convert");
                });
            } catch (Exception e) {
                if (zip != null) zip.delete();
                runOnUiThread(() -> toast(e.getMessage() == null ? "Could not create ZIP" : e.getMessage()));
            }
        });
    }

    private void extractZip(Uri source) {
        worker.execute(() -> {
            try {
                File dir = new File(getCacheDir(), "fileswitch");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Could not prepare files");
                ArrayList<ZipTools.Entry> extracted = ZipTools.extract(getContentResolver(), source, dir);
                runOnUiThread(() -> {
                    files.clear();
                    for (ZipTools.Entry entry : extracted) {
                        OutputFile extractedFile = new OutputFile(entry.file, entry.name, entry.mime, "extracted");
                        Uri fileUri = outputUri(extractedFile);
                        InputFile f = new InputFile(fileUri, entry.name, detectType(fileUri));
                        f.output = extractedFile;
                        f.state = "Extracted";
                        files.add(f);
                    }
                    showPage("Convert");
                    progressText.setText("Extracted " + extracted.size() + " files");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(e.getMessage() == null ? "Could not extract this archive" : e.getMessage()));
            }
        });
    }

    private void processPdf(String operation, ArrayList<Uri> sources, int option, String value) {
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
                            outputs.add(PdfOperations.resizePages(getContentResolver(), source, option, 150, dir));
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
                                outputs.add(PdfOperations.insertImage(getContentResolver(), source, inserted, Integer.parseInt(value), dir));
                            } finally {
                                inserted.recycle();
                            }
                            break;
                        case "compress":
                            int[] dpis = {180, 120, 72};
                            float[] qualities = {.85f, .65f, .4f};
                            outputs.add(PdfOperations.compress(getContentResolver(), source, dpis[option], qualities[option], dir));
                            break;
                        case "crop":
                            float margin = Float.parseFloat(value.trim());
                            if (margin < 0) throw new IOException("Margin must be zero or more");
                            outputs.add(PdfOperations.crop(getContentResolver(), source, margin, dir));
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
                            Bitmap sigBmp = Bitmap.createBitmap(320, 100, Bitmap.Config.ARGB_8888);
                            Canvas sigCanvas = new Canvas(sigBmp);
                            sigCanvas.drawColor(Color.TRANSPARENT);
                            Paint sigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                            sigPaint.setColor(Color.BLUE);
                            sigPaint.setTextSize(24);
                            sigCanvas.drawText(value == null || value.isEmpty() ? "Signed" : value, 10, 60, sigPaint);
                            try {
                                outputs.add(PdfOperations.addSignature(getContentResolver(), source, sigBmp, value, option, dir));
                            } finally {
                                sigBmp.recycle();
                            }
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
                runOnUiThread(() -> {
                    files.clear();
                    InputFile result = new InputFile(sources.get(0), displayName(sources.get(0)), "pdf");
                    for (int i = 1; i < outputs.size(); i++)
                        result.extraOutputs.add(new OutputFile(outputs.get(i), outputs.get(i).getName().replaceFirst("^[^-]+-", ""), "application/pdf", "pdf"));
                    File first = outputs.get(0);
                    result.output = new OutputFile(first, first.getName().replaceFirst("^[^-]+-", ""), "application/pdf", "pdf");
                    result.state = "Ready";
                    files.add(result);
                    batchArchive = null;
                    showPage("Convert");
                    progressText.setText(operationLabel(operation) + " complete");
                });
            } catch (Exception e) {
                runOnUiThread(() -> toast(e.getMessage() == null ? "PDF operation failed" : e.getMessage()));
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
        LinearLayout.LayoutParams p = lp(-1, 52);
        p.topMargin = dp(margin);
        return p;
    }

    private void renderHistory() {
        heading("Recent files", "Your latest conversions on this device.");
        String raw = getPreferences(0).getString("history", "[]");
        try {
            org.json.JSONArray arr = new org.json.JSONArray(raw);
            if (arr.length() == 0) {
                body.addView(text("No conversions yet", 16, true, ink));
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
                remove.setContentDescription("Remove history entry");
                remove.setOnClickListener(v -> deleteHistoryEntry(index));
                LinearLayout.LayoutParams rm = lp(32, 32);
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

    private void showLicensesDialog() {
        String licenses = "FileSwitch for Android\n"
                + "Repository: https://github.com/verma-sankalp/FileSwitch-Android\n"
                + "Developer: Sankalp\n\n"
                + "Open Source Components & Libraries:\n\n"
                + "• Apache PDFBox Android\n"
                + "  Licensed under the Apache License, Version 2.0\n"
                + "  https://github.com/TomRoush/PdfBox-Android\n\n"
                + "• AndroidSVG\n"
                + "  Licensed under the Apache License, Version 2.0\n"
                + "  https://bigbadaboom.github.io/androidsvg/\n\n"
                + "• Google ML Kit Text Recognition\n"
                + "  Licensed under the Apache License, Version 2.0\n"
                + "  https://developers.google.com/ml-kit\n\n"
                + "• AndroidX ExifInterface\n"
                + "  Licensed under the Apache License, Version 2.0\n"
                + "  https://developer.android.com/jetpack/androidx/releases/exifinterface\n\n"
                + "• Apache POI Android\n"
                + "  Licensed under the Apache License, Version 2.0\n"
                + "  https://github.com/jiangjm424/poi-android\n\n"
                + "Apache License 2.0:\n"
                + "Licensed under the Apache License, Version 2.0 (the \"License\"); you may not use these files except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0";

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(surface);
        TextView tv = text(licenses, 13, false, ink);
        tv.setLinkTextColor(accent);
        pad(tv, 18, 14, 18, 14);
        tv.setLineSpacing(dp(2), 1f);
        android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS);
        tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        scroll.addView(tv);

        dialogBuilder()
                .setTitle("Open source licenses")
                .setView(scroll)
                .setNeutralButton("Visit GitHub", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/verma-sankalp/FileSwitch-Android")));
                    } catch (Exception ignored) {}
                })
                .setPositiveButton("Close", null)
                .show();
    }

    private void renderSettings() {
        heading("Settings", "Customize default preferences, privacy and storage.");
        TextView themeLabel = text("Theme", 16, true, ink);
        body.addView(themeLabel);
        Spinner s = new Spinner(this);
        String[] options = {"System", "Light", "Dark"};
        s.setAdapter(createThemedAdapter(options));
        s.setSelection(Arrays.asList(options).indexOf(themeChoice));
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            boolean first = true;
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                String choice = options[pos];
                if (first) {
                    first = false;
                    return;
                }
                themeChoice = choice;
                getPreferences(0).edit().putString("theme", choice).apply();
                showPage("Settings");
            }
        });
        body.addView(s, layoutTop(10));

        LinearLayout destCard = column();
        pad(destCard, 16, 14, 16, 14);
        destCard.setBackground(shape(surface, 16, line));
        destCard.addView(text("Default output destination", 15, true, ink));
        TextView destDesc = text("Files are saved securely via Android's System Document Picker (SAF) to your chosen destination (Downloads, Documents, SD card).", 12, false, muted);
        LinearLayout.LayoutParams dp1 = lp(-1, -2);
        dp1.topMargin = dp(4);
        destCard.addView(destDesc, dp1);
        LinearLayout.LayoutParams dcp = lp(-1, -2);
        dcp.topMargin = dp(16);
        body.addView(destCard, dcp);

        TextView qualLabel = text("Default image quality (" + imageQuality + "%)", 16, true, ink);
        LinearLayout.LayoutParams qlp = lp(-1, -2);
        qlp.topMargin = dp(16);
        body.addView(qualLabel, qlp);
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
        body.addView(qualBar, lp(-1, 42));

        TextView dpiLabel = text("Default resolution", 16, true, ink);
        LinearLayout.LayoutParams dlp = lp(-1, -2);
        dlp.topMargin = dp(16);
        body.addView(dpiLabel, dlp);
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
        body.addView(dpiSpin, layoutTop(8));

        TextView pdfCompLabel = text("Default PDF compression", 16, true, ink);
        LinearLayout.LayoutParams pclp = lp(-1, -2);
        pclp.topMargin = dp(16);
        body.addView(pdfCompLabel, pclp);
        Spinner pdfCompSpin = new Spinner(this);
        String[] pdfCompTitles = {"High quality (180 DPI)", "Balanced (120 DPI)", "Maximum compression (72 DPI)"};
        pdfCompSpin.setAdapter(createThemedAdapter(pdfCompTitles));
        int savedPdfComp = getPreferences(0).getInt("default_pdf_compression", 1);
        pdfCompSpin.setSelection(Math.max(0, Math.min(savedPdfComp, 2)));
        pdfCompSpin.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(AdapterView<?> p) {}
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                getPreferences(0).edit().putInt("default_pdf_compression", pos).apply();
            }
        });
        body.addView(pdfCompSpin, layoutTop(8));

        boolean autoOpen = getPreferences(0).getBoolean("auto_open_result", false);
        View autoOpenCard = toggleCard("Auto-open result", "Automatically open converted file in default viewer", autoOpen, (cb, checked) -> {
            getPreferences(0).edit().putBoolean("auto_open_result", checked).apply();
        });
        LinearLayout.LayoutParams aop = lp(-1, -2);
        aop.topMargin = dp(16);
        body.addView(autoOpenCard, aop);

        boolean preserveMeta = getPreferences(0).getBoolean("preserve_metadata", true);
        View metaCard = toggleCard("Metadata preservation", "Preserve camera EXIF and timestamps in converted images", preserveMeta, (cb, checked) -> {
            getPreferences(0).edit().putBoolean("preserve_metadata", checked).apply();
        });
        LinearLayout.LayoutParams mcp = lp(-1, -2);
        mcp.topMargin = dp(12);
        body.addView(metaCard, mcp);

        boolean keepHist = getPreferences(0).getBoolean("keep_history", true);
        View histCard = toggleCard("Save conversion history", "Log recent conversions locally on this device", keepHist, (cb, checked) -> {
            getPreferences(0).edit().putBoolean("keep_history", checked).apply();
        });
        LinearLayout.LayoutParams hcp = lp(-1, -2);
        hcp.topMargin = dp(12);
        body.addView(histCard, hcp);

        long cacheSize = getCacheSize();
        TextView cacheTitle = text("Storage & Cache", 16, true, ink);
        LinearLayout.LayoutParams clp = lp(-1, -2);
        clp.topMargin = dp(24);
        body.addView(cacheTitle, clp);
        TextView cacheDesc = text("Current temporary conversion cache: " + sizeLabel(cacheSize), 14, false, muted);
        body.addView(cacheDesc, lp(-1, -2));
        body.addView(button("Clear temporary cache now", false, () -> {
            clearCache();
            showPage("Settings");
            toast("Cache cleaned");
        }), layoutTop(10));

        LinearLayout aboutCard = column();
        pad(aboutCard, 16, 14, 16, 14);
        aboutCard.setBackground(shape(surface, 16, line));
        aboutCard.addView(text("FileSwitch v1.0", 16, true, ink));
        TextView aboutDesc = text("Fast, 100% offline & local file conversion utility for Android.\nDeveloper: Sankalp\nFileSwitch-Android-version", 12, false, muted);
        aboutDesc.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams abp = lp(-1, -2);
        abp.topMargin = dp(6);
        aboutCard.addView(aboutDesc, abp);
        aboutCard.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/verma-sankalp/FileSwitch-Android")));
            } catch (Exception ignored) {}
        });
        LinearLayout.LayoutParams acp = lp(-1, -2);
        acp.topMargin = dp(24);
        body.addView(aboutCard, acp);

        body.addView(button("Open source licenses", false, this::showLicensesDialog), layoutTop(10));
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onBackPressed() {
        if (!"Convert".equals(page)) {
            showPage("Convert");
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        worker.shutdown();
        if (notifManager != null) notifManager.dismissAll();
        super.onDestroy();
    }
}
