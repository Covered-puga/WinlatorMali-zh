package com.winlator.cmod;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.style.BackgroundColorSpan;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.preference.PreferenceManager;

import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.core.ArrayUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.logs.LogFiles;
import com.winlator.cmod.logs.SessionLog;

import org.json.JSONArray;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The Log Manager, after Bannerlator's: where logs go, what is recorded (each switch explained), housekeeping, and the
 * logs on disk per game with a viewer (find, wrap, copy), share and delete. Opened from Settings › Advanced, and from
 * "View logs" on a shortcut or container, which opens that game's logs directly ({@link #ARG_GAME}).
 */
public class LogManagerFragment extends Fragment {
    public static final String ARG_GAME = "game";
    private static final int REQUEST_CUSTOM_FOLDER = 4711;
    private static final int[] KEEP_OPTIONS = {0, 1, 3, 5, 10, 20};
    private static final long VIEW_TAIL_BYTES = 512 * 1024;
    private static final String[] COMMON_WINE_CHANNELS = {"err", "warn", "fixme", "seh", "module", "loaddll", "process", "thread",
        "sync", "file", "reg", "heap", "ntdll", "d3d", "dxgi", "vulkan", "opengl", "relay"};

    private SharedPreferences prefs;
    private LinearLayout content;
    private int onSurface, muted, accent, surface;

    public static LogManagerFragment forGame(String gameName) {
        LogManagerFragment fragment = new LogManagerFragment();
        Bundle args = new Bundle();
        args.putString(ARG_GAME, gameName);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.log_manager_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        Context context = requireContext();
        prefs = PreferenceManager.getDefaultSharedPreferences(context);
        onSurface = ThemeManager.getOnSurfaceTextColor(context);
        muted = (onSurface & 0x00FFFFFF) | 0xA6000000;
        accent = ThemeManager.getAccentColor(context);
        surface = ThemeManager.getSurfaceColor(context);
        content = view.findViewById(R.id.LLLogContent);
        if (getActivity() instanceof AppCompatActivity && ((AppCompatActivity) getActivity()).getSupportActionBar() != null)
            ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.log_manager);
        build();

        String game = getArguments() != null ? getArguments().getString(ARG_GAME) : null;
        if (game != null && savedInstanceState == null) {
            LogFiles.Group group = LogFiles.forGame(context, game);
            if (group != null) showGroup(group);
            else toast(LogFiles.isOn(context, LogFiles.PREF_PER_GAME)
                ? getString(R.string.log_manager_no_game_logs, game)
                : getString(R.string.log_manager_per_game_off));
        }
    }

    private void build() {
        Context context = requireContext();
        content.removeAllViews();
        boolean enabled = LogFiles.isOn(context, LogFiles.PREF_ENABLED);

        // Master switch
        LinearLayout master = card();
        master.setPadding(dp(14), dp(14), dp(14), dp(14));
        toggle(master, getString(R.string.logs), enabled ? getString(R.string.log_manager_enabled) : getString(R.string.log_manager_disabled),
            LogFiles.PREF_ENABLED, getString(R.string.log_manager_info_master), null);

        // Where logs go
        section(getString(R.string.log_manager_where));
        LinearLayout where = card();
        String mode = prefs.getString(LogFiles.PREF_LOCATION, LogFiles.LOCATION_DOCUMENTS);
        int modeLabelId = LogFiles.LOCATION_DOWNLOAD.equals(mode) ? R.string.log_manager_download
                : LogFiles.LOCATION_CUSTOM.equals(mode) ? R.string.log_manager_custom_folder : R.string.log_manager_documents;
        String modeLabel = getString(modeLabelId);
        pickRow(where, getString(R.string.log_manager_save_to), modeLabel, LogFiles.root(context).getAbsolutePath(), () ->
            ContentDialog.showSingleChoiceList(context, getString(R.string.log_manager_save_to), new String[]{getString(R.string.log_manager_documents), getString(R.string.log_manager_download), getString(R.string.log_manager_choose_folder)}, index -> {
                if (index == 2) {
                    try { startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_CUSTOM_FOLDER); }
                    catch (Exception e) { toast(getString(R.string.log_manager_missing_picker)); }
                    return;
                }
                prefs.edit().putString(LogFiles.PREF_LOCATION, index == 1 ? LogFiles.LOCATION_DOWNLOAD : LogFiles.LOCATION_DOCUMENTS).apply();
                build();
            }));
        toggle(where, getString(R.string.log_manager_per_game), getString(R.string.log_manager_per_game_hint), LogFiles.PREF_PER_GAME, getString(R.string.log_manager_info_per_game), null);

        // What to record
        section(getString(R.string.log_manager_what));
        LinearLayout what = card();
        if (!enabled) what.setAlpha(0.45f);
        toggle(what, getString(R.string.log_manager_wine_debug), getString(R.string.log_manager_wine_hint), LogFiles.PREF_WINE, getString(R.string.log_manager_info_wine), null);
        wineChannels(what);
        toggle(what, getString(R.string.log_manager_box64), getString(R.string.log_manager_box64_hint), LogFiles.PREF_BOX64, getString(R.string.log_manager_info_box64), null);
        toggle(what, getString(R.string.log_manager_dxvk_vkd3d), getString(R.string.log_manager_dxvk_hint), LogFiles.PREF_DXVK, getString(R.string.log_manager_info_dxvk), null);
        toggle(what, getString(R.string.log_manager_android_logcat), getString(R.string.log_manager_logcat_hint), LogFiles.PREF_LOGCAT, getString(R.string.log_manager_info_logcat), null);
        toggle(what, getString(R.string.log_manager_crash_reports), getString(R.string.log_manager_crash_hint), LogFiles.PREF_CRASH, getString(R.string.log_manager_info_crash), null);
        toggle(what, getString(R.string.log_manager_exit_autosave), getString(R.string.log_manager_exit_hint), LogFiles.PREF_EXIT_AUTOSAVE, getString(R.string.log_manager_info_exit),
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R ? null : getString(R.string.log_manager_needs_android11));

        // Housekeeping
        section(getString(R.string.log_manager_housekeeping));
        LinearLayout house = card();
        int keep = LogFiles.keepRuns(context);
        pickRow(house, getString(R.string.log_manager_keep_last), keep == 0 ? getString(R.string.log_manager_latest_only) : getString(R.string.log_manager_per_game_runs, keep), getString(R.string.log_manager_cleanup_hint), () -> {
            String[] labels = new String[KEEP_OPTIONS.length];
            for (int i = 0; i < KEEP_OPTIONS.length; i++) labels[i] = KEEP_OPTIONS[i] == 0 ? getString(R.string.log_manager_latest_only) : getString(R.string.log_manager_past_runs, KEEP_OPTIONS[i]);
            ContentDialog.showSingleChoiceList(context, getString(R.string.log_manager_keep_last), labels, index -> {
                prefs.edit().putInt(LogFiles.PREF_KEEP_RUNS, KEEP_OPTIONS[index]).apply();
                build();
            });
        });
        List<LogFiles.Group> groups = LogFiles.scan(context);
        LinearLayout actions = new LinearLayout(context);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(8), 0, 0);
        actions.addView(action(getString(R.string.log_manager_logcat_now), () -> runInBackground(getString(R.string.log_manager_capturing), () -> {
            File out = SessionLog.captureAppLogcat(context);
            return () -> { build(); toast(getString(R.string.log_manager_saved_file, out.getName())); };
        })), weight());
        actions.addView(action(getString(R.string.log_manager_exit_reasons), () -> runInBackground(getString(R.string.log_manager_reading_records), () -> {
            String text = SessionLog.exitReasons(context, true);
            return () -> { build(); showText(getString(R.string.log_manager_exits_title), text, null); };
        })), weight());
        actions.addView(action(getString(R.string.log_manager_clear_all), () -> {
            if (groups.isEmpty()) { toast(getString(R.string.log_manager_nothing_to_delete)); return; }
            ContentDialog.confirm(context, getString(R.string.log_manager_confirm_delete_all, LogFiles.humanBytes(LogFiles.totalBytes(groups))), () -> {
                for (LogFiles.Group g : groups) LogFiles.deleteGroup(context, g);
                build();
            });
        }), weight());
        house.addView(actions);

        // Logs by game
        section(getString(R.string.log_manager_game_logs, LogFiles.humanBytes(LogFiles.totalBytes(groups))));
        if (groups.isEmpty()) {
            LinearLayout empty = card();
            TextView tv = text(getString(R.string.log_manager_empty), 13.5f, muted);
            tv.setGravity(Gravity.CENTER);
            empty.addView(tv);
        }
        for (LogFiles.Group g : groups) {
            LinearLayout row = card();
            row.setClickable(true);
            row.setFocusable(true);
            row.setOnClickListener(v -> showGroup(g));
            TextView title = text(g.isApp() ? getString(R.string.log_manager_app_log_types) : g.name, 15.5f, onSurface);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(title);
            String fileCount = g.fileCount == 0 ? getString(R.string.log_manager_no_current_files)
                    : g.fileCount == 1 ? getString(R.string.file_count_one) : getString(R.string.file_count_many, g.fileCount);
            String pastRuns = "";
            if (g.archivedRuns > 0) {
                pastRuns = " · " + (g.archivedRuns == 1 ? getString(R.string.log_manager_one_past_run) : getString(R.string.log_manager_past_runs, g.archivedRuns));
            }
            row.addView(text(fileCount + pastRuns
                + " · " + LogFiles.humanBytes(g.bytes)
                + " · " + DateUtils.getRelativeTimeSpanString(g.lastModified, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS), 12.5f, muted));
        }

        LinearLayout note = new LinearLayout(context);
        note.setPadding(dp(4), dp(12), dp(4), 0);
        note.addView(text(getString(R.string.log_manager_privacy_note), 11.5f, muted));
        content.addView(note);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (content != null) build();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CUSTOM_FOLDER || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        String path = FileUtils.getFilePathFromUri(requireContext(), data.getData());
        if (path == null || !new File(path).canWrite()) {
            toast(getString(R.string.log_manager_folder_unwritable));
            return;
        }
        prefs.edit().putString(LogFiles.PREF_LOCATION, LogFiles.LOCATION_CUSTOM).putString(LogFiles.PREF_CUSTOM_PATH, path).apply();
        build();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Wine debug channels

    private void wineChannels(LinearLayout parent) {
        Context context = requireContext();
        List<String> channels = new ArrayList<>(Arrays.asList(prefs.getString("wine_debug_channels", SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS).split(",")));
        channels.removeIf(String::isEmpty);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(4), 0, 0, dp(6));
        box.addView(text(getString(R.string.log_manager_channels), 11.5f, accent));
        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(context);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(0, dp(4), 0, 0);
        for (String channel : channels) {
            TextView chip = chip(channel + "  ×", true);
            chip.setOnClickListener(v -> {
                channels.remove(channel);
                saveChannels(channels);
            });
            chips.addView(chip);
        }
        TextView add = chip(getString(R.string.log_manager_add), false);
        add.setOnClickListener(v -> {
            List<String> options = new ArrayList<>();
            for (String c : COMMON_WINE_CHANNELS) if (!channels.contains(c)) options.add(c);
            options.add(getString(R.string.log_manager_all_channels));
            ContentDialog.showSingleChoiceList(context, getString(R.string.log_manager_add_channel), options.toArray(new String[0]), index -> {
                if (index == options.size() - 1) {
                    String[] all;
                    try { all = ArrayUtils.toStringArray(new JSONArray(FileUtils.readString(context, "wine_debug_channels.json"))); }
                    catch (Exception e) { all = new String[0]; }
                    final String[] items = all;
                    ContentDialog.showMultipleChoiceList(context, R.string.wine_debug_channel, items, selected -> {
                        for (int i : selected) if (!channels.contains(items[i])) channels.add(items[i]);
                        saveChannels(channels);
                    });
                }
                else {
                    channels.add(options.get(index));
                    saveChannels(channels);
                }
            });
        });
        chips.addView(add);
        TextView reset = chip(getString(R.string.reset), false);
        reset.setOnClickListener(v -> saveChannels(new ArrayList<>(Arrays.asList(SettingsFragment.DEFAULT_WINE_DEBUG_CHANNELS.split(",")))));
        chips.addView(reset);
        hs.addView(chips);
        box.addView(hs);
        parent.addView(box);
    }

    private void saveChannels(List<String> channels) {
        List<String> unique = new ArrayList<>(new LinkedHashSet<>(channels));
        if (unique.isEmpty()) prefs.edit().remove("wine_debug_channels").apply();
        else prefs.edit().putString("wine_debug_channels", TextUtils.join(",", unique)).apply();
        build();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // A game's logs

    /** A game's runs and their files; pick a file to view it, or share / delete them all. */
    private void showGroup(LogFiles.Group group) {
        Context context = requireContext();
        List<String> labels = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (LogFiles.Run run : LogFiles.runs(group.dir)) {
            String when = run.current ? getString(R.string.log_manager_latest_run) : DateUtils.formatDateTime(context, run.millis,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH);
            for (File f : LogFiles.filesIn(run.dir)) {
                labels.add(getString(R.string.log_manager_file_label, when, f.getName(), LogFiles.humanBytes(f.length())));
                actions.add(() -> viewFile(f));
            }
        }
        String what = group.isApp() ? getString(R.string.log_manager_app_logs_object) : group.name;
        labels.add(getString(R.string.log_manager_share_group, what));
        actions.add(() -> shareZip(group.name, group.dir));
        labels.add(getString(R.string.log_manager_delete_group, what));
        actions.add(() -> ContentDialog.confirm(context, getString(R.string.log_manager_confirm_delete_group,
                LogFiles.humanBytes(group.bytes), TextUtils.htmlEncode(what)), () -> {
            LogFiles.deleteGroup(context, group);
            build();
        }));
        ContentDialog.showSingleChoiceList(context, group.isApp() ? getString(R.string.log_manager_app_logs) : group.name, labels.toArray(new String[0]), index -> {
            if (index >= 0 && index < actions.size()) actions.get(index).run();
        });
    }

    /** The end of a log (the last 512 KB, where a crash is) in the viewer. */
    private void viewFile(File file) {
        Context context = requireContext();
        runInBackground(getString(R.string.log_manager_opening, file.getName()), () -> {
            String text;
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                long length = raf.length();
                long start = Math.max(0, length - VIEW_TAIL_BYTES);
                byte[] data = new byte[(int) (length - start)];
                raf.seek(start);
                raf.readFully(data);
                text = (start > 0 ? context.getString(R.string.log_manager_tail, LogFiles.humanBytes(VIEW_TAIL_BYTES), LogFiles.humanBytes(length)) : "")
                    + new String(data, java.nio.charset.StandardCharsets.UTF_8);
            }
            catch (Exception e) {
                text = context.getString(R.string.log_manager_read_file_failed, file.getName(), e.getMessage());
            }
            final String body = text;
            return () -> showText(file.getName(), body, file);
        });
    }

    /** Monospace viewer with find (next match highlighted), wrap, copy and share. */
    private void showText(String title, String body, @Nullable File file) {
        Context context = requireContext();
        TextView tv = text(body, 10.5f, onSurface);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        HorizontalScrollView hs = new HorizontalScrollView(context);
        hs.addView(tv);
        ScrollView sv = new ScrollView(context);
        sv.addView(hs);
        sv.post(() -> sv.fullScroll(View.FOCUS_DOWN));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        EditText find = new EditText(context);
        find.setHint(R.string.log_manager_find);
        find.setSingleLine(true);
        find.setTextSize(13);
        find.setTextColor(onSurface);
        find.setHintTextColor(muted);
        bar.addView(find, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        final int[] from = {0};
        TextView next = chip(getString(R.string.log_manager_next), false);
        next.setOnClickListener(v -> {
            String query = find.getText().toString();
            if (query.isEmpty()) return;
            String lower = body.toLowerCase(Locale.ROOT);
            int at = lower.indexOf(query.toLowerCase(Locale.ROOT), from[0]);
            if (at < 0) at = lower.indexOf(query.toLowerCase(Locale.ROOT));
            if (at < 0) { toast(getString(R.string.item_not_found)); return; }
            from[0] = at + query.length();
            SpannableString span = new SpannableString(body);
            span.setSpan(new BackgroundColorSpan((accent & 0x00FFFFFF) | 0x99000000), at, at + query.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(span);
            final int match = at;
            tv.post(() -> {
                if (tv.getLayout() == null) return;
                int line = tv.getLayout().getLineForOffset(match);
                sv.smoothScrollTo(0, Math.max(0, tv.getLayout().getLineTop(line) - dp(60)));
                hs.smoothScrollTo((int) tv.getLayout().getPrimaryHorizontal(match) - dp(40), 0);
            });
        });
        bar.addView(next);
        final boolean[] wrapped = {false};
        TextView wrap = chip(getString(R.string.log_manager_wrap), false);
        wrap.setOnClickListener(v -> {
            wrapped[0] = !wrapped[0];
            if (tv.getParent() instanceof ViewGroup) ((ViewGroup) tv.getParent()).removeView(tv);
            sv.removeAllViews();
            if (wrapped[0]) sv.addView(tv);
            else { hs.addView(tv); sv.addView(hs); }
            wrap.setText(wrapped[0] ? getString(R.string.log_manager_no_wrap) : getString(R.string.log_manager_wrap));
        });
        bar.addView(wrap);
        TextView copy = chip(getString(R.string.copy), false);
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText(title, body));
            toast(getString(R.string.log_manager_copied));
        });
        bar.addView(copy);

        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.addView(bar);
        panel.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (int) (context.getResources().getDisplayMetrics().heightPixels * 0.55f)));

        ContentDialog dialog = new ContentDialog(context);
        dialog.setTitle(title);
        ViewGroup slot = dialog.getContentView().findViewById(R.id.FrameLayout);
        slot.addView(panel);
        slot.setVisibility(View.VISIBLE);
        if (file != null) dialog.setOnConfirmCallback(() -> share(file, "text/plain"));
        dialog.show();
        TextView confirm = dialog.findViewById(R.id.BTConfirm);
        if (confirm != null) {
            if (file != null) confirm.setText(R.string.share);
            else confirm.setVisibility(View.GONE);
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Sharing

    /** Zip a game's (or the app's) logs, all kept runs, and share the zip. */
    private void shareZip(String name, File dir) {
        Context context = requireContext();
        runInBackground(getString(R.string.log_manager_packing), () -> {
            File zip = new File(context.getCacheDir(), "logs/" + LogFiles.folderName(name) + "_logs_" + LogFiles.timestamp(System.currentTimeMillis()) + ".zip");
            zip.getParentFile().mkdirs();
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zip))) {
                for (LogFiles.Run run : LogFiles.runs(dir)) {
                    String prefix = run.current ? "" : LogFiles.ARCHIVE_DIR + "/" + run.dir.getName() + "/";
                    for (File f : LogFiles.filesIn(run.dir)) {
                        zos.putNextEntry(new ZipEntry(prefix + f.getName()));
                        try (FileInputStream in = new FileInputStream(f)) {
                            byte[] buf = new byte[64 * 1024];
                            int n;
                            while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
                        }
                        zos.closeEntry();
                    }
                }
            }
            catch (Exception e) {
                return () -> toast(getString(R.string.log_manager_pack_failed, e.getMessage()));
            }
            return () -> share(zip, "application/zip");
        });
    }

    private void share(File file, String mime) {
        Context context = getContext();
        if (context == null) return;
        try {
            Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".tileprovider", file);
            Intent send = new Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.log_manager_share_subject, file.getName()))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(send, getString(R.string.log_manager_share_log)));
        }
        catch (Exception e) {
            toast(getString(R.string.log_manager_share_failed, e.getMessage()));
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Building blocks (all colors from the theme preset)

    private void section(String title) {
        TextView tv = text(title, 13.5f, accent);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(4), dp(16), dp(4), dp(6));
        content.addView(tv);
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(surface);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        content.addView(card, lp);
        return card;
    }

    /** A switch with its one-line hint and an ⓘ for the full explanation; {@code disabledWhy} greys it out. */
    private void toggle(LinearLayout parent, String title, String hint, String pref, String info, @Nullable String disabledWhy) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(4), 0, dp(4));
        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.addView(text(title, 14.5f, onSurface));
        texts.addView(text(disabledWhy != null ? disabledWhy : hint, 11.5f, muted));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        ImageView infoButton = new ImageView(context);
        infoButton.setImageResource(R.drawable.icon_about);
        infoButton.setColorFilter(muted);
        infoButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        infoButton.setOnClickListener(v -> showText(title, info, null));
        row.addView(infoButton, new LinearLayout.LayoutParams(dp(36), dp(36)));
        CheckBox box = new CheckBox(context);
        box.setChecked(LogFiles.PREF_ENABLED.equals(pref) ? LogFiles.isOn(context, pref) : LogFiles.savedValue(context, pref));
        box.setEnabled(disabledWhy == null);
        box.setButtonTintList(android.content.res.ColorStateList.valueOf(accent));
        box.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(pref, checked).apply();
            if (LogFiles.PREF_ENABLED.equals(pref)) content.post(this::build);
        });
        row.addView(box);
        row.setOnClickListener(v -> { if (box.isEnabled()) box.toggle(); });
        if (disabledWhy != null) row.setAlpha(0.5f);
        parent.addView(row);
    }

    /** A setting that opens a choice: title, current value (accent), and a detail line. */
    private void pickRow(LinearLayout parent, String title, String value, String detail, Runnable onClick) {
        Context context = requireContext();
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(4), 0, dp(6));
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> onClick.run());
        LinearLayout top = new LinearLayout(context);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(text(title, 14.5f, onSurface), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView v = text(value + "  ▾", 14f, accent);
        v.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        top.addView(v);
        row.addView(top);
        row.addView(text(detail, 11.5f, muted));
        parent.addView(row);
    }

    private TextView action(String label, Runnable onClick) {
        TextView tv = chip(label, false);
        tv.setGravity(Gravity.CENTER);
        tv.setOnClickListener(v -> onClick.run());
        return tv;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(38), 1);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private TextView chip(String label, boolean filled) {
        TextView tv = text(label, 12.5f, filled ? onSurface : accent);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(6), dp(12), dp(6));
        tv.setSingleLine(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        if (filled) bg.setColor((accent & 0x00FFFFFF) | 0x40000000);
        else bg.setStroke(dp(1), (accent & 0x00FFFFFF) | 0x99000000);
        tv.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(6));
        tv.setLayoutParams(lp);
        tv.setClickable(true);
        tv.setFocusable(true);
        return tv;
    }

    private TextView text(String s, float sp, int color) {
        TextView tv = new TextView(requireContext());
        tv.setText(s);
        tv.setTextSize(sp);
        tv.setTextColor(color);
        return tv;
    }

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private interface Work {
        Runnable run();
    }

    /** Disk and process work off the main thread, with the app's loading dialog; the result runs on the UI thread. */
    private void runInBackground(String message, Work work) {
        MainActivity activity = getActivity() instanceof MainActivity ? (MainActivity) getActivity() : null;
        if (activity != null) activity.preloaderDialog.show(message);
        Executors.newSingleThreadExecutor().execute(() -> {
            Runnable onDone;
            try { onDone = work.run(); }
            catch (Exception e) { onDone = () -> toast(e.getMessage()); }
            final Runnable done = onDone;
            if (getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (activity != null) activity.preloaderDialog.close();
                if (isAdded() && done != null) done.run();
            });
        });
    }

    private void toast(String text) {
        if (getContext() != null) Toast.makeText(getContext(), text, Toast.LENGTH_LONG).show();
    }

}
