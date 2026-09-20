package com.modcritic.invmgr.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.function.DoubleSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The original's {@code @media (pointer: coarse)} block, checked both ways round.
 *
 * <p>No window and no JavaFX: the table is a table, and the two things worth proving about it are
 * that a desktop gets exactly the numbers it had before any of this existed, and that a phone gets
 * numbers that are actually bigger.
 *
 * <p><b>The first of those is the one that matters.</b> A phone layout is only worth having if it
 * cannot reach the desktop, and every entry in this table is a place where it could: one method
 * that forgot to ask, or asked and returned the wrong side of the pair, and every desktop control
 * quietly changes size with nothing to say so.
 */
class TouchTypeTest {

    @AfterEach
    void putThePlatformBack() {
        System.clearProperty(Device.OVERRIDE_PROPERTY);
    }

    private static void onAPhone() {
        System.setProperty(Device.OVERRIDE_PROPERTY, "android");
    }

    private static void onADesktop() {
        System.setProperty(Device.OVERRIDE_PROPERTY, "desktop");
    }

    /** Every method in the table, paired with the desktop token it must return on a desktop. */
    private static final Object[][] TABLE = {
        {"dialog min width", (DoubleSupplier) TouchType::dialogMinWidth, Tokens.DIALOG_MIN_WIDTH},
        {"dialog padding", (DoubleSupplier) TouchType::dialogPadding, Tokens.DIALOG_PADDING},
        {"dialog title", (DoubleSupplier) TouchType::dialogTitleFont, Tokens.FONT_DIALOG_TITLE},
        {"dialog type", (DoubleSupplier) TouchType::dialogFont, Tokens.FONT_CONTROL},
        {"dialog input padding V", (DoubleSupplier) TouchType::dialogInputPaddingV,
            Tokens.DIALOG_INPUT_PADDING_V},
        {"dialog input padding H", (DoubleSupplier) TouchType::dialogInputPaddingH,
            Tokens.DIALOG_INPUT_PADDING_H},
        {"dialog number width", (DoubleSupplier) TouchType::dialogNumberWidth,
            Tokens.DIALOG_NUMBER_WIDTH},
        {"dialog text width", (DoubleSupplier) TouchType::dialogTextWidth, Tokens.DIALOG_TEXT_WIDTH},
        {"dialog button padding V", (DoubleSupplier) TouchType::dialogButtonPaddingV,
            Tokens.DIALOG_BUTTON_PADDING_V},
        {"dialog button padding H", (DoubleSupplier) TouchType::dialogButtonPaddingH,
            Tokens.DIALOG_BUTTON_PADDING_H},
        {"dialog button height", (DoubleSupplier) TouchType::dialogButtonHeight,
            Tokens.DIALOG_BUTTON_HEIGHT},
        {"dialog number height", (DoubleSupplier) TouchType::dialogNumberHeight,
            Tokens.DIALOG_NUMBER_HEIGHT},
        {"ID box width", (DoubleSupplier) TouchType::idInputWidth, Tokens.DIALOG_ID_WIDTH},
        {"ID caption", (DoubleSupplier) TouchType::idLabelFont, Tokens.FONT_ID_LABEL},
        {"ID box type", (DoubleSupplier) TouchType::idInputFont, Tokens.FONT_ID_LABEL},
        {"list row type", (DoubleSupplier) TouchType::listRowFont, Tokens.FONT_LIST_ROW},
        {"list row padding V", (DoubleSupplier) TouchType::listRowPaddingV,
            Tokens.LIST_ROW_PADDING_V},
        {"list row padding H", (DoubleSupplier) TouchType::listRowPaddingH,
            Tokens.LIST_ROW_PADDING_H},
        {"list header padding V", (DoubleSupplier) TouchType::listHeaderPaddingV,
            Tokens.LIST_HEADER_PADDING_V},
        {"list header padding H", (DoubleSupplier) TouchType::listHeaderPaddingH,
            Tokens.LIST_HEADER_PADDING_H},
        {"list header type", (DoubleSupplier) TouchType::listHeaderFont, Tokens.FONT_LIST_HEADER},
        {"export button", (DoubleSupplier) TouchType::exportButtonSize,
            Tokens.LIST_EXPORT_BUTTON_SIZE},
        {"export glyph", (DoubleSupplier) TouchType::exportButtonFont, Tokens.FONT_CONTROL},
        {"search type", (DoubleSupplier) TouchType::searchFont, Tokens.FONT_SEARCH},
        {"search padding V", (DoubleSupplier) TouchType::searchPaddingV,
            Tokens.DIALOG_INPUT_PADDING_V},
        {"search padding H", (DoubleSupplier) TouchType::searchPaddingH,
            Tokens.DIALOG_INPUT_PADDING_H},
        {"search clear type", (DoubleSupplier) TouchType::searchClearFont, Tokens.FONT_CONTROL},
        {"status type", (DoubleSupplier) TouchType::statusFont, Tokens.FONT_STATUS},
        {"status padding V", (DoubleSupplier) TouchType::statusPaddingV, Tokens.STATUS_PADDING_V},
        {"status padding H", (DoubleSupplier) TouchType::statusPaddingH, Tokens.STATUS_PADDING_H},
        {"preset box", (DoubleSupplier) TouchType::presetSize, Tokens.PRESET_SIZE},
        {"preset type", (DoubleSupplier) TouchType::presetFont, Tokens.FONT_PRESET},
        {"preset plus", (DoubleSupplier) TouchType::presetAddFont, Tokens.FONT_PRESET_ADD},
        {"dialog button type", (DoubleSupplier) TouchType::dialogButtonFont, Tokens.FONT_CONTROL},
    };

    /**
     * The entries that are deliberately <b>the same number on a phone as on a desktop</b>.
     *
     * <p>Each transcribes a rule the original never actually applies: its {@code @media (pointer:
     * coarse)} declaration sits <em>earlier</em> in the stylesheet than a base rule of equal
     * specificity, so the cascade has always given a phone the desktop value. Fourteen rules are in
     * that position ({@code TouchType}'s class documentation lists them with line numbers), and
     * these are the two the phone actually complained about, so these are the two put back. The
     * other twelve keep their touch sizes by the user's decision of 2026-08-13.
     *
     * <p><b>Naming them is the point, not a hole in the test.</b> "This one does not change" becomes
     * a claim the suite checks, instead of an absence it cannot see; without it, a method that
     * quietly stopped asking about the platform at all would be indistinguishable from these.
     */
    private static final Set<String> SAME_ON_BOTH = Set.of(
            // The whole dialog. The user compared the two side by side on 2026-08-13 and asked for
            // the dialogs to match the original, after the intended sizes made the Add Item dialog
            // wider than a 360 px screen.
            "dialog min width",
            "dialog padding",
            "dialog title",
            "dialog type",
            "dialog input padding V",
            "dialog input padding H",
            "dialog number width",
            "dialog text width",
            "dialog number height",
            "dialog button padding V",
            "dialog button padding H",
            "dialog button type",
            "ID box width",
            "ID caption",
            "ID box type",
            // And the status bar, whose oversizing pushed its tip off the edge of the screen.
            "status type",
            "status padding V",
            "status padding H");

    @Test
    @DisplayName("a desktop gets exactly the numbers it had before any of this existed")
    void aDesktopIsUntouched() {
        onADesktop();
        for (Object[] entry : TABLE) {
            assertEquals((double) entry[2], ((DoubleSupplier) entry[1]).getAsDouble(), 1e-9,
                    entry[0] + " must be the desktop's own number on a desktop");
        }
    }

    @Test
    @DisplayName("and a phone changes every one of them except the six that must not change")
    void aPhoneChangesEveryOne() {
        // Bounded the other way, and it is not the same claim. The test above passes perfectly on
        // a table where every method ignores the platform and returns the desktop value, which is
        // to say, on a table that does nothing at all.
        onADesktop();
        double[] desktop = new double[TABLE.length];
        for (int i = 0; i < TABLE.length; i++) {
            desktop[i] = ((DoubleSupplier) TABLE[i][1]).getAsDouble();
        }

        onAPhone();
        for (int i = 0; i < TABLE.length; i++) {
            String what = (String) TABLE[i][0];
            double onPhone = ((DoubleSupplier) TABLE[i][1]).getAsDouble();
            if (SAME_ON_BOTH.contains(what)) {
                // Asserted equal rather than merely skipped. Skipping would let this entry drift to
                // any value at all; the whole reason it is listed is that its value is decided.
                assertEquals(desktop[i], onPhone, 1e-9,
                        what + " is one of the original's dead rules and must be the SAME on both");
            } else {
                assertTrue(onPhone != desktop[i], what + " should change on a phone and did not");
            }
        }
    }

    @Test
    @DisplayName("and everything a finger touches gets bigger, not smaller")
    void nothingShrinksForAFinger() {
        // The heights are the exception and are checked separately below: they are not a size that
        // grows for a fingertip but a browser minimum that stops binding.
        //
        // The status bar is NOT in this list any more, and that is the change worth noticing. It
        // used to be, on the strength of the original's `#status-bar { font-size: 13px }` inside the
        // touch block, a rule the cascade discards. The phone renders 11 px there, the same as a
        // desktop, and the oversized 13 is what pushed the tip past the edge of the screen.
        onAPhone();
        assertTrue(TouchType.listRowFont() > Tokens.FONT_LIST_ROW);
        assertTrue(TouchType.listRowPaddingV() > Tokens.LIST_ROW_PADDING_V);
        assertTrue(TouchType.presetSize() > Tokens.PRESET_SIZE);
        assertTrue(TouchType.searchFont() > Tokens.FONT_SEARCH);
        assertTrue(TouchType.exportButtonSize() > Tokens.LIST_EXPORT_BUTTON_SIZE);
    }

    @Test
    @DisplayName("and the whole dialog is the original's size, because the original's is what fits")
    void theDialogIsNotGrownForAFinger() {
        // The other half of the pair above, and the one with a screen behind it. Implementing the
        // sizes the original ASKS for made the Add Item dialog wider than a 360 px phone: its third
        // preset, its + button and the Name placeholder all clipped off the right-hand edge. The
        // sizes the original RENDERS are the desktop's, and they fit.
        onAPhone();
        assertEquals(Tokens.FONT_CONTROL, TouchType.dialogFont(), 1e-9);
        assertEquals(Tokens.FONT_DIALOG_TITLE, TouchType.dialogTitleFont(), 1e-9);
        assertEquals(Tokens.DIALOG_PADDING, TouchType.dialogPadding(), 1e-9);
        assertEquals(Tokens.DIALOG_NUMBER_WIDTH, TouchType.dialogNumberWidth(), 1e-9);
        assertEquals(Tokens.DIALOG_TEXT_WIDTH, TouchType.dialogTextWidth(), 1e-9);
        assertEquals(Tokens.FONT_ID_LABEL, TouchType.idInputFont(), 1e-9);

        // The room's own W/L/H fields are the counter-example and must NOT be dragged along with
        // them: `#top-bar label` and `#top-bar input` are declared before the touch block, so those
        // overrides are live, the original really does grow them, and the user asked to keep them.
        assertTrue(Tokens.FONT_CONTROL_TOUCH_MAX > Tokens.FONT_CONTROL,
                "the top bar's own fields still grow for a fingertip");
    }

    @Test
    @DisplayName("both dialog pins stay on for a phone, and the button's is worked out not typed")
    void thePinsStayOn() {
        // A browser gives a button and a number box a minimum height that owes nothing to the font,
        // which is why they are pinned at all. Both pins used to come off on a phone, and the
        // reasoning was sound while it lasted: at 16 px type the natural height already exceeded
        // them, so holding them would have clipped the text. That premise is gone (the dialog
        // renders 13 px on a phone too), so the pins are as right here as on a desktop.
        onAPhone();
        assertEquals(Tokens.DIALOG_NUMBER_HEIGHT, TouchType.dialogNumberHeight(), 1e-9);

        // The button cannot simply keep the desktop's 30, and that is the interesting half: JavaFX's
        // line box for this face is about 1.6 em where the browser's is 1.18, so a computed height
        // lands 31 px against the reference's 27.3. It is pinned to what the original renders.
        double expected = Tokens.TOUCH_TEXT_LINE_EM * Tokens.FONT_CONTROL
                + 2 * Tokens.DIALOG_BUTTON_PADDING_V + 2 * Tokens.TOUCH_BUTTON_BORDER;
        assertEquals(expected, TouchType.dialogButtonHeight(), 1e-9);

        // Bounded on both sides, because "27.34" on its own would pass just as well if the constant
        // were nonsense: it has to be near the 27.3 measured off the original's own screenshot.
        assertTrue(Math.abs(TouchType.dialogButtonHeight() - 27.3) < 1.0,
                "the pinned height must match the reference's 27.3, not merely be a number");
    }
}
