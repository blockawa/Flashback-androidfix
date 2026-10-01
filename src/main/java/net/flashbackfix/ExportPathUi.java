package net.flashbackfix;

import com.moulberry.flashback.Flashback;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import imgui.moulberry90.ImGui;
import imgui.moulberry90.type.ImString;
import net.minecraft.client.resources.language.I18n;

import java.nio.file.Path;
import java.util.List;

/**
 * Export folder picker drawn inside Flashback's Preferences window: a path field with a
 * Browse button opening a self-made ImGui directory browser instead of the native dialog
 * that hangs on Android.
 *
 * <p>Path semantics live in {@link ExportPathUtil} (relative against the game folder,
 * absolute as-is - ReplayMod's {@code advanced.renderPath} rule).
 */
public final class ExportPathUi {

    private static final ImString pathInput = ImGuiHelper.createResizableImString("");
    private static final String POPUP_SUFFIX = "###flashbackandroidfix_picker";

    private static String syncedPath = null;
    private static Path pickerDir = null;

    private ExportPathUi() {}

    public static void render(FlashbackConfigV1 config) {
        Path folder = ExportPathUtil.configuredFolder(config);

        ImGuiHelper.separatorWithText(I18n.get("flashbackandroidfix.export_location"));

        if (ExportPathUtil.isPathForced(config)) {
            ImGui.textDisabled(ExportPathUtil.relativeDisplay(folder));
            ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.forced_tooltip", String.valueOf(folder)));
            return;
        }

        String current = ExportPathUtil.relativeDisplay(folder);
        if (!current.equals(syncedPath)) {
            pathInput.set(current);
            syncedPath = current;
        }

        String browseLabel = I18n.get("flashbackandroidfix.browse");
        float browseWidth = ImGuiHelper.calcTextWidth(browseLabel) + 16f;
        float spacing = ImGui.getStyle().getItemSpacingX();

        ImGui.setNextItemWidth(Math.max(80f, ImGui.getContentRegionAvailX() - browseWidth - spacing));
        ImGui.inputText("##flashbackandroidfix_path", pathInput);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            applyFromInput(config);
        }
        ImGuiHelper.tooltip(I18n.get("flashbackandroidfix.path_tooltip", String.valueOf(folder)));

        ImGui.sameLine();
        if (ImGui.button(browseLabel, browseWidth, 0)) {
            pickerDir = folder;
            ImGui.openPopup(pickerPopup());
        }

        ImGui.textDisabled(I18n.get("flashbackandroidfix.path_hint"));

        renderPicker(config);
    }

    private static void applyFromInput(FlashbackConfigV1 config) {
        Path resolved = ExportPathUtil.resolvePath(ImGuiHelper.getString(pathInput));
        if (resolved == null) {
            // Not a valid path - drop the edit and show the stored value again
            syncedPath = null;
            return;
        }
        ExportPathUtil.applyFolder(config, resolved);
    }

    private static String pickerPopup() {
        return I18n.get("flashbackandroidfix.picker_title") + POPUP_SUFFIX;
    }

    private static void renderPicker(FlashbackConfigV1 config) {
        Path gameDir = ExportPathUtil.gameDir();
        if (gameDir == null) return;

        ImGui.setNextWindowSize(ReplayUI.scaleUi(380), 0);
        if (!ImGui.beginPopupModal(pickerPopup())) {
            return;
        }

        Path dir = pickerDir;
        if (dir == null) {
            dir = gameDir;
            pickerDir = dir;
        }

        ImGui.textWrapped(ExportPathUtil.relativeDisplay(dir));

        Path parent = dir.getParent();
        if (parent == null) {
            ImGui.beginDisabled();
            ImGui.button(I18n.get("flashbackandroidfix.picker_up"));
            ImGui.endDisabled();
        } else if (ImGui.button(I18n.get("flashbackandroidfix.picker_up"))) {
            pickerDir = parent;
        }

        float childWidth = ImGui.getContentRegionAvailX();
        ImGui.beginChild("##flashbackandroidfix_dirs", childWidth, ReplayUI.scaleUi(220), true);

        List<Path> subDirectories = ExportPathUtil.listSubdirectories(dir);
        if (subDirectories.isEmpty()) {
            ImGui.textDisabled(I18n.get("flashbackandroidfix.picker_empty"));
        } else {
            float rowWidth = ImGui.getContentRegionAvailX();
            for (Path sub : subDirectories) {
                ImGui.pushID(sub.toString());
                boolean clicked = ImGui.button(sub.getFileName().toString(), rowWidth, 0);
                ImGui.popID();
                if (clicked) {
                    pickerDir = sub;
                    break;
                }
            }
        }
        ImGui.endChild();

        float buttonSpacing = ImGui.getStyle().getItemSpacingX();
        float buttonWidth = (ImGui.getContentRegionAvailX() - buttonSpacing) / 2f;

        if (ImGui.button(I18n.get("flashbackandroidfix.picker_select"), buttonWidth, 0)) {
            ExportPathUtil.applyFolder(config, pickerDir);
            ImGui.closeCurrentPopup();
        }
        ImGui.sameLine();
        if (ImGui.button(I18n.get("gui.cancel"), buttonWidth, 0)) {
            ImGui.closeCurrentPopup();
        }

        ImGui.endPopup();
    }
}
