package com.modcritic.invmgr.ui;

import com.modcritic.invmgr.model.AppState;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

/**
 * The window the Menu button opens: switches and pages that do not belong on the bar.
 *
 * <p>The first piece of interface the original (the single-file HTML app this one rewrites) never
 * had, so there is no reference screenshot for it. The user approved it as a mockup on 2026-09-24,
 * and every size here is in {@link Tokens} under "the Menu", built from the colors the rest of the
 * app already uses.
 *
 * <p>The entries: <b>Metric</b>, the Units button's job moved out of the bar, and <b>Theme</b>,
 * drawn gray with "coming soon" until themes exist to switch between. <b>Reset Room</b>, in the
 * red the app keeps for things that cannot be undone, hands over to {@link ResetRoomDialog}.
 * <b>About</b> opens the app's information pages, and <b>Settings</b> is shown only on a phone,
 * because its one option only means anything there. <b>Feedback</b> was added on 2026-09-26. Only
 * entries that work are ever shown; see {@link TopBar}'s class comment on inert buttons.
 *
 * <h2>Pages</h2>
 *
 * <p>About opens a page inside this same square, and it has two of its own: Privacy policy and
 * Credits. Settings is a page too. Each page below the Menu has a {@code ‹} in its title row that
 * goes back one page, and Escape and the phone's Back do the same, which is what Back means
 * everywhere else on a phone. On the Menu itself they close the window. The {@code ×} and a tap
 * outside close it from any page, and it always opens on the Menu.
 *
 * <h2>One box, always the same size</h2>
 *
 * <p>The window is a fixed square, and later pages (Settings, About) replace what is in it rather
 * than opening a second window on top. That is the user's rule, so that a translucent theme later
 * can never show one window through another. Fixed size is also what lets a replacement page land
 * exactly where the Menu was.
 *
 * <p>On a desktop the square is {@link Tokens#MENU_WINDOW_SIDE}, 334 px. On a phone it is worked out
 * from the screen by {@link #phoneSide}, which comes to the same 334 on the test phone either way
 * up.
 *
 * <h2>Closing it</h2>
 *
 * <p>Four ways, and none of them loses anything, because the window holds no half-filled form: the
 * {@code ×} in the title row, a tap on the darkened room outside it, Escape, and the phone's Back
 * button, which {@code InvMgrActivity} already delivers as Escape.
 */
public final class MenuWindow extends ModalDialog {

    private final Overlays host;

    /** What the phone's own bars take right now. Read on every layout, because rotation moves them. */
    private final Supplier<SystemInsets> insets;

    private AppState state;

    private Runnable onMetric = () -> { };
    private Runnable onResetRoom = () -> { };
    private Consumer<String> onOpenLink = url -> { };
    private Consumer<Boolean> onHideSystemBars = hidden -> { };
    private Consumer<String> onFeedbackByEmail = message -> { };
    private Consumer<String> onFeedbackByIssue = message -> { };

    /** Whether the bars are hidden now, as the app last said. The toggle shows this. */
    private boolean barsHidden;

    /** Which page the square is showing. Each page below MENU goes back to the one it came from. */
    enum Page {
        MENU("Menu", null),
        SETTINGS("Settings", MENU),
        FEEDBACK_NOTICE("Feedback", MENU),
        FEEDBACK("Feedback", MENU),
        ABOUT("About", MENU),
        PRIVACY("Privacy policy", ABOUT),
        CREDITS("Credits", ABOUT);

        final String title;
        final Page parent;

        Page(String title, Page parent) {
            this.title = title;
            this.parent = parent;
        }
    }

    private Page page = Page.MENU;

    private final Button close = titleButton("×");
    private final Button back = titleButton("‹");
    private final Label pageTitle = new Label();

    private final Button metric;
    private final HBox theme;
    private final Button about;
    private final Button resetRoom;

    private final Button feedback;
    private final Button feedbackOk = pageButton("OK", Dialogs.ButtonKind.CONFIRM);
    private final TextArea feedbackText = new SkinnedTextArea();
    private final Button byEmail = pageButton("Email", Dialogs.ButtonKind.CONFIRM);
    private final Button byIssue = pageButton("GitHub", Dialogs.ButtonKind.CONFIRM);
    /** What happened to the last message: handed to which app, or why not. */
    private final Label feedbackResult = new Label();

    /** Null off the phone, where there is nothing to set yet. */
    private final Button settingsEntry;
    private final Button hideBars = toggleButton();

    private final Button sourceCode;
    private final Button privacy;
    private final Button credits;

    /** Each page's body, built once. Privacy's words arrive from the app; see setPrivacyPolicy. */
    private final Node menuBody;
    private final Node aboutBody;
    private final VBox privacyText = new VBox(Tokens.MENU_ENTRY_GAP);
    private final Node privacyBody;
    private final Node creditsBody;
    private final Node settingsBody;
    private final Node feedbackNoticeBody;
    private final Node feedbackBody;

    /** The label on the right of the Metric entry, which reads "on" or "off". */
    private final Label metricState = rightLabel("", TouchType.menuFont());

    public MenuWindow(Overlays host, AppState state, Supplier<SystemInsets> insets) {
        super(host);
        this.host = host;
        this.state = state;
        this.insets = insets;

        VBox box = content();
        box.setPadding(new Insets(TouchType.menuPadding()));

        close.setOnAction(event -> hide());
        back.setOnAction(event -> goBack());
        pageTitle.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuTitleFont()));
        textColor(pageTitle, Tokens.TEXT_INPUT);

        metric = entry("Metric", metricState);
        metric.setOnAction(event -> {
            onMetric.run();
            refresh();
        });
        theme = inertEntry("Theme", "coming soon");

        about = plainEntry("About", "›");
        about.setOnAction(event -> showPage(Page.ABOUT));

        // Last, where the spec lists it, and red. It closes this window before handing over, so
        // the Reset Room window replaces the Menu rather than opening on top of it.
        resetRoom = entry("Reset Room", rightLabel("", TouchType.menuFont()));
        styleEntry(resetRoom, Tokens.BUTTON_DANGER_BG, Tokens.BUTTON_DANGER_BORDER,
                Tokens.TEXT_PRIMARY);
        resetRoom.setOnAction(event -> {
            hide();
            onResetRoom.run();
        });

        VBox entries = new VBox(Tokens.MENU_ENTRY_GAP, metric, theme);
        if (Device.isAndroid()) {
            settingsEntry = plainEntry("Settings", "›");
            settingsEntry.setOnAction(event -> showPage(Page.SETTINGS));
            entries.getChildren().add(settingsEntry);
        } else {
            settingsEntry = null;
        }
        feedback = plainEntry("Feedback", "›");
        feedback.setOnAction(event -> showPage(Page.FEEDBACK_NOTICE));
        entries.getChildren().addAll(feedback, about, resetRoom);
        // Six entries on a phone no longer fit the 334 px square, which five filled to within a
        // pixel. The user decided on 2026-09-25 that the page then scrolls WITH A VISIBLE
        // SCROLLBAR, which on a phone is ScrollHint's, since JavaFX draws none there until a
        // finger is already scrolling.
        menuBody = scrolling(Tokens.MENU_ENTRY_GAP, entries.getChildren().toArray(Node[]::new));

        feedbackOk.setOnAction(event -> showPage(Page.FEEDBACK));
        feedbackNoticeBody = scrolling(Tokens.MENU_PROSE_GAP,
                prose(FEEDBACK_NOTICE, Tokens.TEXT_DIALOG_LABEL),
                prose(FEEDBACK_ROUTES, Tokens.TEXT_DIALOG_LABEL), Dialogs.buttonRow(feedbackOk));

        feedbackText.setWrapText(true);
        feedbackText.setPrefRowCount(4);
        feedbackText.setPromptText("What would you like to say?");
        feedbackText.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuPageProseFont()));
        feedbackText.setStyle("-fx-control-inner-background: " + Tokens.hex(Tokens.DIALOG_INPUT_BG)
                + "; -fx-text-fill: " + Tokens.hex(Tokens.TEXT_INPUT) + ";"
                + "-fx-prompt-text-fill: "
                + Tokens.hex(Tokens.placeholderOver(Tokens.DIALOG_INPUT_BG)) + ";"
                + "-fx-background-color: " + Tokens.hex(Tokens.CONTROL_BORDER) + ", "
                + Tokens.hex(Tokens.DIALOG_INPUT_BG) + ";"
                + "-fx-background-insets: 0, 1; -fx-background-radius: 0;");
        Dialogs.limitLength(feedbackText, AppInfo.FEEDBACK_MAX_LENGTH);
        byEmail.setOnAction(event -> sendFeedback(onFeedbackByEmail, message -> true,
                "Opened in your mail app. Send it from there."));
        byIssue.setOnAction(event -> sendFeedback(onFeedbackByIssue, AppInfo::fitsInIssueUrl,
                "Opened in your browser. Post it from there; GitHub needs you signed in."));
        feedbackResult.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuPageProseFont()));
        feedbackResult.setWrapText(true);
        textColor(feedbackResult, Tokens.TEXT_ID_LABEL);
        // The box, the buttons and nothing between them. Where each button goes is said on the
        // warning page instead: under the box it pushed the buttons 16 px below the fold on a
        // phone (TouchLayoutTest.theFeedbackPageOnAPhone).
        feedbackBody = scrolling(Tokens.MENU_PROSE_GAP, feedbackText,
                Dialogs.buttonRow(byIssue, byEmail), feedbackResult);

        hideBars.setOnAction(event -> onHideSystemBars.accept(!barsHidden));
        Label hideBarsName = new Label("Hide system bars");
        hideBarsName.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuFont()));
        textColor(hideBarsName, Tokens.TEXT_DIALOG_LABEL);
        Region hideBarsGap = new Region();
        HBox.setHgrow(hideBarsGap, Priority.ALWAYS);
        HBox hideBarsRow = new HBox(Tokens.MENU_ENTRY_GAP, hideBarsName, hideBarsGap, hideBars);
        hideBarsRow.setAlignment(Pos.CENTER_LEFT);
        settingsBody = scrolling(Tokens.MENU_PROSE_GAP, hideBarsRow, prose(HIDE_BARS_NOTE,
                Tokens.TEXT_ID_LABEL));
        refreshHideBars();

        // The one link out of the app. Its arrow is drawn in the math face, the only bundled face
        // that has it; see Fonts.SYMBOL_FAMILY.
        sourceCode = plainEntry("Source code", "↗");
        noteOf(sourceCode).setFont(Fonts.symbol(TouchType.menuFont()));
        sourceCode.setOnAction(event -> onOpenLink.accept(AppInfo.SOURCE_URL));
        privacy = plainEntry("Privacy policy", "›");
        privacy.setOnAction(event -> showPage(Page.PRIVACY));
        credits = plainEntry("Credits", "›");
        credits.setOnAction(event -> showPage(Page.CREDITS));

        Label version = new Label("InvMgr " + AppInfo.VERSION);
        version.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuFont()));
        textColor(version, Tokens.TEXT_INPUT);
        Label description = prose(AppInfo.DESCRIPTION, Tokens.TEXT_ID_LABEL);
        aboutBody = scrolling(Tokens.MENU_ABOUT_GAP, version, description,
                new VBox(Tokens.MENU_ENTRY_GAP, sourceCode, privacy, credits));

        privacyBody = scrolling(Tokens.MENU_PROSE_GAP, privacyText);
        creditsBody = scrolling(Tokens.MENU_PROSE_GAP, AppInfo.CREDITS.stream()
                .map(line -> prose(line, Tokens.TEXT_DIALOG_LABEL)).toArray(Node[]::new));

        refresh();
        showPage(Page.MENU);
    }

    /** What the Metric entry does. The app points it at {@link TopBar#toggleMetric}. */
    public void setOnMetric(Runnable handler) {
        this.onMetric = handler == null ? () -> { } : handler;
    }

    /** What the Reset Room entry opens. The app points it at {@link ResetRoomDialog#open}. */
    public void setOnResetRoom(Runnable handler) {
        this.onResetRoom = handler == null ? () -> { } : handler;
    }

    /**
     * What the Source code entry does with its address. The app opens it in a browser: an Android
     * intent on a phone, {@code HostServices.showDocument} on a desktop. This app never fetches it
     * itself.
     */
    public void setOnOpenLink(Consumer<String> handler) {
        this.onOpenLink = handler == null ? url -> { } : handler;
    }

    /**
     * What the Hide system bars toggle does. The app points it at its own setting, which hides
     * or shows the bars, saves the choice, and then tells this window through
     * {@link #setHideSystemBars}.
     */
    public void setOnHideSystemBars(Consumer<Boolean> handler) {
        this.onHideSystemBars = handler == null ? hidden -> { } : handler;
    }

    /** Shows whether the bars are hidden now. */
    public void setHideSystemBars(boolean hidden) {
        this.barsHidden = hidden;
        refreshHideBars();
    }

    /** What the Feedback entry says first, before the message box. */
    static final String FEEDBACK_NOTICE = "Sending feedback needs the internet. InvMgr never"
            + " goes online itself: it hands your message to your mail app or your browser, and"
            + " they send it.";

    /** On the warning page, under the warning: where each of the next page's buttons sends it. */
    static final String FEEDBACK_ROUTES = "Email opens your mail app, addressed to "
            + AppInfo.FEEDBACK_EMAIL + ". GitHub opens a public issue in your browser, and needs"
            + " a GitHub account.";

    /**
     * What the GitHub button says instead of opening an address GitHub would refuse. Only a message
     * full of characters outside plain ASCII gets here; see {@link AppInfo#ISSUE_URL_MAX}.
     */
    static final String TOO_LONG_FOR_GITHUB = "Too long for a GitHub link. Shorten it, or send it"
            + " by Email.";

    /** What Feedback's Email button does with the message. The app hands it to a mail app. */
    public void setOnFeedbackByEmail(Consumer<String> handler) {
        this.onFeedbackByEmail = handler == null ? message -> { } : handler;
    }

    /** What Feedback's GitHub button does with the message. The app opens a new issue with it. */
    public void setOnFeedbackByIssue(Consumer<String> handler) {
        this.onFeedbackByIssue = handler == null ? message -> { } : handler;
    }

    /**
     * The mockup's wording, with one change: it said "the top or bottom edge", and on its side
     * the phone's navigation bar is on the right, so it now names the edge the bar was on.
     */
    static final String HIDE_BARS_NOTE = "Hides the clock and battery bar and the navigation"
            + " bar. Swipe in from the edge a bar was on to see them for a moment.";

    private void refreshHideBars() {
        hideBars.setText(barsHidden ? "On" : "Off");
        if (barsHidden) {
            styleButton(hideBars, Tokens.TOGGLE_METRIC_BG, Tokens.TOGGLE_METRIC_BORDER,
                    Tokens.TOGGLE_METRIC_TEXT, Tokens.MENU_ENTRY_PADDING_H);
        } else {
            styleButton(hideBars, Tokens.BUTTON_TOGGLE_BG, Tokens.CONTROL_BORDER,
                    Tokens.TEXT_PRIMARY, Tokens.MENU_ENTRY_PADDING_H);
        }
    }

    /**
     * The privacy policy's paragraphs, from {@link AppInfo#privacyPolicy}. Handed in by the app,
     * which knows which platform this is and where the autosave lives.
     */
    public void setPrivacyPolicy(List<String> paragraphs) {
        privacyText.getChildren().setAll(paragraphs.stream()
                .map(line -> prose(line, Tokens.TEXT_DIALOG_LABEL)).toArray(Node[]::new));
    }

    /** Points the window at a freshly loaded room, whose metric switch may differ. */
    public void setState(AppState newState) {
        this.state = newState;
        refresh();
    }

    /**
     * Yes, except on the Feedback page. A tap outside is the same as Back here ({@link #cancel}):
     * it closes the Menu, and on any other page goes back one. On the message page that would put
     * away a half-written message and the keyboard with it, so there a stray tap does nothing, for
     * the same reason the original's dialogs never close on one. The message is kept anyway, for
     * when the page is left some other way.
     */
    @Override
    protected boolean closesOnOutsideTap() {
        return page != Page.FEEDBACK;
    }

    /** Escape and the phone's Back: back one page, or closed from the Menu itself. */
    @Override
    protected void cancel() {
        if (page == Page.MENU) {
            hide();
        } else {
            goBack();
        }
    }

    @Override
    protected void onShown() {
        showPage(Page.MENU);
        relayout();
        refresh();
        // Focus inside the window, or Escape never reaches it. A key goes to the focused node and
        // travels up that node's own parents; the canvas behind is not inside this overlay, so
        // while it held focus the Escape filter was never on the route. Same for the phone's Back.
        content().requestFocus();
    }

    /**
     * Sizes the square and keeps it clear of the phone's bars.
     *
     * <p>Called on show, and again by the app on every rotation, because turning the phone moves
     * the navigation bar and changes the space the window is centered in.
     */
    public void relayout() {
        double side = side();
        VBox box = content();
        box.setMinSize(side, side);
        box.setPrefSize(side, side);
        box.setMaxSize(side, side);

        SystemInsets now = Device.isTouch() ? insets.get() : SystemInsets.NONE;
        setSafeArea(new Insets(now.top(), now.right(), now.bottom(), now.left()));
    }

    /** Redraws the Metric entry from the room's own switch. */
    public void refresh() {
        boolean on = state.metricMode;
        metricState.setText(on ? "on" : "off");
        if (on) {
            styleEntry(metric, Tokens.TOGGLE_METRIC_BG, Tokens.TOGGLE_METRIC_BORDER,
                    Tokens.TOGGLE_METRIC_TEXT);
        } else {
            styleEntry(metric, Tokens.BUTTON_TOGGLE_BG, Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY);
        }
    }

    /** How big the square is on this device, right now. */
    double side() {
        if (!Device.isTouch()) {
            return Tokens.MENU_WINDOW_SIDE;
        }
        return phoneSide(host.getWidth(), host.getHeight(), insets.get());
    }

    /**
     * The window's edge on a phone, from the screen's size and what its bars take.
     *
     * <p>The user's rule (2026-09-24) is half the height of the phone held upright, and the same
     * size turned on its side. Worked out in two halves, and the smallest number wins.
     *
     * <p><b>The same both ways up</b>: three limits that come out identical whichever way the phone
     * is held, because each reads the screen's long or short side rather than its current width or
     * height.
     *
     * <ul>
     *   <li><b>Half the upright height</b> between the status bar and the navigation bar. On the
     *       test phone that is (740 − 24 − 48) / 2 = 334.
     *   <li><b>The height left below the status bar on its side</b>, 360 − 24 = 336 on the test
     *       phone. A taller phone is why this limit exists: at 360 × 800 half the upright
     *       height is 364, which will not go sideways, so both come out at 336.
     *   <li><b>The upright width</b>, less {@link Tokens#MENU_WINDOW_SIDE_MARGIN} a side, so the
     *       window never touches the edges of a narrow phone. It matters with the bars hidden (the
     *       Settings option), where the first two limits are 370 and 360 and this one, 344,
     *       decides.
     * </ul>
     *
     * <p><b>On the glass right now</b>: the width and height the app actually has at this moment.
     * Neither bites on the test phone. They are here because the second limit above <b>assumes the
     * navigation bar moves to the side when the phone turns</b>, which it does on the test phone;
     * one that keeps the bar along the bottom has less height on its side than that limit thinks,
     * and these keep the window on the screen regardless.
     *
     * <p>Pure, and taking the insets as an argument, so the arithmetic can be checked at any screen
     * size without a phone or a window.
     */
    static double phoneSide(double width, double height, SystemInsets bars) {
        double longSide = Math.max(width, height);
        double shortSide = Math.min(width, height);
        double navigation = Math.max(bars.bottom(), Math.max(bars.left(), bars.right()));
        double margins = 2 * Tokens.MENU_WINDOW_SIDE_MARGIN;

        double halfUpright = (longSide - bars.top() - navigation) / 2;
        double sideways = shortSide - bars.top();
        double uprightWidth = shortSide - margins;
        double sameBothWays = Math.min(halfUpright, Math.min(sideways, uprightWidth));

        double widthNow = width - bars.left() - bars.right() - margins;
        double heightNow = height - bars.top() - bars.bottom();
        double onTheGlass = Math.min(widthNow, heightNow);

        return Math.floor(Math.min(sameBothWays, onTheGlass));
    }

    // -------------------------------------------------------------- building

    /**
     * Puts a page in the square, replacing whatever was there: a title row, then the page's body.
     *
     * <p>Replacing rather than stacking is the window's rule (see the class comment), and it is
     * also why the square never changes size between pages.
     */
    private void showPage(Page next) {
        page = next;
        pageTitle.setText(next.title);
        Node body = switch (next) {
            case MENU -> menuBody;
            case ABOUT -> aboutBody;
            case PRIVACY -> privacyBody;
            case CREDITS -> creditsBody;
            case SETTINGS -> settingsBody;
            case FEEDBACK_NOTICE -> feedbackNoticeBody;
            case FEEDBACK -> feedbackBody;
        };
        content().getChildren().setAll(titleRow(next != Page.MENU), body);
        // ⚠ And focus back inside the window, every time. Swapping a page out removes whatever
        // had focus with it (the ‹ that was just pressed, or the entry), and JavaFX then hands
        // focus to something outside this window, where Escape and the phone's Back never reach
        // it. Found by MenuWindowAppTest.backGoesBackOnePage: the second Escape, back on the Menu,
        // did nothing.
        content().requestFocus();
        if (next == Page.FEEDBACK) {
            // Straight to the box, so a desktop can type at once. A phone shows the caret and
            // waits for a tap before raising the keyboard: JavaFX's Android skin for a text area
            // raises it only on a click on a focused box (TextAreaSkinAndroid, read 2026-09-25),
            // the same as every other box in the app.
            //
            // The box sits inside a scroll pane, and a scroll pane puts its content into the scene
            // only once it has a skin; before that, asking for focus silently does nothing.
            // applyCss builds the skin now. Waiting a turn with runLater was tried first and is not
            // enough, because a queued task can run before the next layout pass:
            // MenuWindowAppTest.feedbackWarnsFirst caught focus still on the window, with the box
            // already in the scene by then.
            content().applyCss();
            feedbackText.requestFocus();
        }
    }

    /**
     * What the Feedback page's two buttons do: hand the message to the app's chosen route, and say
     * what happened.
     *
     * <p>"Opened in your mail app", not "sent": InvMgr hands the message over and never learns
     * whether it went, so the page says only that it was handed over. The message stays in the
     * box, so the other button still has it.
     *
     * <p>{@code fits} is the route's own limit, checked before anything leaves: the GitHub button
     * passes {@link AppInfo#fitsInIssueUrl}, and Email has none.
     */
    private void sendFeedback(Consumer<String> route, Predicate<String> fits, String handedOver) {
        String message = feedbackText.getText() == null ? "" : feedbackText.getText().strip();
        if (message.isEmpty()) {
            feedbackResult.setText("Write something first.");
            return;
        }
        if (!fits.test(message)) {
            feedbackResult.setText(TOO_LONG_FOR_GITHUB);
            return;
        }
        route.accept(message);
        feedbackResult.setText(handedOver);
    }

    private void goBack() {
        if (page.parent != null) {
            showPage(page.parent);
        }
    }

    /**
     * The page's heading, with {@code ‹} at the left below the Menu, the close button at the
     * right, and the dialogs' hairline under all of it.
     */
    private HBox titleRow(boolean withBack) {
        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);

        HBox row = new HBox(Tokens.MENU_ENTRY_GAP);
        if (withBack) {
            row.getChildren().add(back);
        }
        row.getChildren().addAll(pageTitle, gap, close);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, 0, Tokens.DIALOG_TITLE_PADDING_BOTTOM, 0));
        Dialogs.headingUnderline(row);
        VBox.setMargin(row, new Insets(0, 0, Tokens.DIALOG_TITLE_MARGIN_BOTTOM, 0));
        return row;
    }

    /**
     * One entry: a full-width button with its name on the left and a note on the right.
     *
     * <p>The two labels are a single graphic stretched across the button. A {@code Button} lays its
     * graphic out at the graphic's own width, so the graphic's width is tied to the button's; the
     * gap in the middle then takes up the rest and pushes the note to the far end.
     */
    private static Button entry(String name, Label note) {
        Label left = new Label(name);
        left.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuFont()));

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox inside = new HBox(left, gap, note);
        inside.setAlignment(Pos.CENTER_LEFT);

        Button button = new Button();
        button.setGraphic(inside);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setMaxWidth(Double.MAX_VALUE);
        pinHeight(button, TouchType.menuEntryHeight());
        inside.prefWidthProperty().bind(button.widthProperty()
                .subtract(2 * Tokens.MENU_ENTRY_PADDING_H + 2));
        return button;
    }

    /**
     * An entry that opens something: plain gray, with a hover on a desktop.
     *
     * @param note the mark at the right-hand end, {@code ›} for a page and {@code ↗} for a link
     */
    private static Button plainEntry(String name, String note) {
        Button button = entry(name, rightLabel(note, TouchType.menuFont()));
        styleEntry(button, Tokens.BUTTON_BG, Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY);
        if (!Device.isTouch()) {
            button.setOnMouseEntered(event -> styleEntry(button, Tokens.BUTTON_BG_HOVER,
                    Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY));
            button.setOnMouseExited(event -> styleEntry(button, Tokens.BUTTON_BG,
                    Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY));
        }
        return button;
    }

    /**
     * The Settings page's On/Off switch: as tall as an entry, as wide as its words, and the Metric
     * entry's green when on, the one color this window already uses for "switched on".
     */
    private static Button toggleButton() {
        Button button = new Button("Off");
        button.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuFont()));
        pinHeight(button, TouchType.menuEntryHeight());
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    /** The label at the right-hand end of an entry. */
    private static Label noteOf(Button entry) {
        HBox inside = (HBox) entry.getGraphic();
        return (Label) inside.getChildren().get(2);
    }

    /** A paragraph on one of the About pages. */
    private static Label prose(String text, Color color) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuPageProseFont()));
        label.setWrapText(true);
        textColor(label, color);
        return label;
    }

    /**
     * A page's body, which scrolls when it is taller than the square.
     *
     * <p>Two pages scroll on the test phone (the phone this project's device tests run on): the
     * Menu, with six entries, and the Privacy policy. A phone draws no scrollbar of its own until
     * a finger is already scrolling, so text below the fold would be text nobody knows is there;
     * {@link ScrollHint} draws one that shows. Styled the item list's way, in the window's own gray
     * so it shows no box of its own. On Android a finger arrives twice, as a touch and as a
     * synthesized mouse copy, and the copy is what scrolls lists.
     */
    private static Node scrolling(double gap, Node... children) {
        VBox inside = new VBox(gap, children);
        String gray = Tokens.hex(Tokens.DIALOG_BG);
        inside.setStyle("-fx-background-color: " + gray + ";");
        ScrollPane scroller = new ScrollPane(inside);
        scroller.setFitToWidth(true);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroller.setStyle("-fx-background: " + gray + "; -fx-background-color: " + gray + ";"
                + "-fx-padding: 0; -fx-background-insets: 0;");
        // A finger that scrolls the page does not also press the entry it started on, and the page
        // stops at its ends rather than stretching past them and springing back.
        DragCancelsTap.install(scroller);
        ScrollEnds.install(scroller);
        // On a phone, a scrollbar that shows: ScrollHint's, because JavaFX's own appears there only
        // while a finger is already scrolling (the user's rule, 2026-09-25).
        Node body = ScrollHint.around(scroller);
        VBox.setVgrow(body, Priority.ALWAYS);
        return body;
    }

    /** A button inside a page, in the Menu's own sizes. */
    private static Button pageButton(String text, Dialogs.ButtonKind kind) {
        return Dialogs.button(text, kind, TouchType.menuFont(), TouchType.menuButtonHeight());
    }

    /**
     * An entry for something that does not work yet: gray, and not a button at all.
     *
     * <p>A disabled {@code Button} would do, except JavaFX draws a disabled control at 40% opacity
     * whatever its colors say, which turns the gray into a smudge. This is a plain row with the
     * look set directly, and nothing to press.
     */
    private static HBox inertEntry(String name, String note) {
        Label left = new Label(name);
        left.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuFont()));
        textColor(left, Tokens.MENU_INERT_TEXT);

        Label right = rightLabel(note, Tokens.FONT_MENU_NOTE);
        textColor(right, Tokens.MENU_INERT_TEXT);

        Region gap = new Region();
        HBox.setHgrow(gap, Priority.ALWAYS);
        HBox row = new HBox(left, gap, right);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(0, Tokens.MENU_ENTRY_PADDING_H, 0, Tokens.MENU_ENTRY_PADDING_H));
        row.setMinHeight(TouchType.menuEntryHeight());
        row.setPrefHeight(TouchType.menuEntryHeight());
        row.setMaxHeight(TouchType.menuEntryHeight());
        row.setStyle("-fx-background-color: " + Tokens.hex(Tokens.DIALOG_BG) + ";"
                + "-fx-border-color: " + Tokens.hex(Tokens.MENU_INERT_BORDER) + ";"
                + "-fx-border-width: 1;");
        return row;
    }

    private static Label rightLabel(String text, double fontSize) {
        Label label = new Label(text);
        label.setFont(Font.font(Tokens.FONT_FAMILY, fontSize));
        return label;
    }

    /** The square {@code ×} in the title row: four fifths of an entry's height. */
    private static Button titleButton(String glyph) {
        Button button = new Button(glyph);
        button.setFont(Font.font(Tokens.FONT_FAMILY, TouchType.menuFont()));
        double side = Tokens.MENU_TITLE_BUTTON_SHARE * TouchType.menuEntryHeight();
        button.setMinSize(side, side);
        button.setPrefSize(side, side);
        button.setMaxSize(side, side);
        styleButton(button, Tokens.BUTTON_BG, Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY, 0);
        hover(button, Tokens.BUTTON_BG, 0);
        return button;
    }

    /**
     * Paints an entry, and its labels with it.
     *
     * <p>The labels get their color as an inline style, the way every recolor in this app does it.
     * The trap where a node with an inline background loses a {@code setTextFill} to a derived
     * inline color (JavaFX derives the text color inline too, and inline beats code) does not reach
     * these labels: they sit inside the button and have no background of their own. Measured, not
     * reasoned: {@code MenuWindowAppTest} reads the words' pixels off the screen, and they came out
     * right with {@code setTextFill} as well (2026-09-24). The inline style stays so there is one
     * way text gets its color.
     */
    private static void styleEntry(Button button, Color background, Color border, Color text) {
        styleButton(button, background, border, text, Tokens.MENU_ENTRY_PADDING_H);
        if (button.getGraphic() instanceof HBox inside) {
            inside.getChildren().forEach(child -> {
                if (child instanceof Label label) {
                    textColor(label, text);
                }
            });
        }
    }

    private static void styleButton(Button button, Color background, Color border, Color text,
            double paddingH) {
        button.setStyle("-fx-background-color: " + Tokens.hex(background) + ";"
                + "-fx-text-fill: " + Tokens.hex(text) + ";"
                + "-fx-border-color: " + Tokens.hex(border) + ";"
                + "-fx-border-width: 1;"
                + "-fx-background-radius: 0; -fx-border-radius: 0;"
                + "-fx-padding: 0 " + paddingH + " 0 " + paddingH + ";");
    }

    /**
     * Lightens a plain button under the pointer, on a desktop only.
     *
     * <p>Not on a phone, for the reason {@code TopBar.onHover} gives: Android leaves its made-up
     * mouse wherever the last tap was, so a hover color would stick to whatever slid under it.
     */
    private static void hover(Button button, Color resting, double paddingH) {
        if (Device.isTouch()) {
            return;
        }
        button.setOnMouseEntered(event -> styleButton(button, Tokens.BUTTON_BG_HOVER,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY, paddingH));
        button.setOnMouseExited(event -> styleButton(button, resting,
                Tokens.CONTROL_BORDER, Tokens.TEXT_PRIMARY, paddingH));
    }

    private static void textColor(Label label, Color color) {
        label.setStyle("-fx-text-fill: " + Tokens.hex(color) + ";");
    }

    private static void pinHeight(Region region, double height) {
        region.setMinHeight(height);
        region.setPrefHeight(height);
        region.setMaxHeight(height);
    }

    // ------------------------------------------------------------ for tests

    public Button closeButton() {
        return close;
    }

    public Button metricEntry() {
        return metric;
    }

    public HBox themeEntry() {
        return theme;
    }

    public Button resetRoomEntry() {
        return resetRoom;
    }

    public Button aboutEntry() {
        return about;
    }

    /** Null off the phone. */
    public Button settingsEntry() {
        return settingsEntry;
    }

    public Button hideSystemBarsToggle() {
        return hideBars;
    }

    public Button feedbackEntry() {
        return feedback;
    }

    public Button feedbackOkButton() {
        return feedbackOk;
    }

    public TextArea feedbackText() {
        return feedbackText;
    }

    public Button feedbackByEmailButton() {
        return byEmail;
    }

    public Button feedbackByIssueButton() {
        return byIssue;
    }

    public Label feedbackResult() {
        return feedbackResult;
    }

    public Button sourceCodeEntry() {
        return sourceCode;
    }

    public Button privacyEntry() {
        return privacy;
    }

    public Button creditsEntry() {
        return credits;
    }

    public Button backButton() {
        return back;
    }

    public Label pageTitle() {
        return pageTitle;
    }

    /** The words on the page showing now, in order: what the tests read the pages by. */
    List<String> pageText() {
        return content().lookupAll(".label").stream()
                .map(node -> ((Label) node).getText())
                .toList();
    }

    public VBox panel() {
        return content();
    }
}
