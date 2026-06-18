// SPDX-FileCopyrightText: 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include "frontend_common/config.h"

class AndroidConfig final : public Config {
public:
    explicit AndroidConfig(const std::string& config_name = "config",
                           ConfigType config_type = ConfigType::GlobalConfig);

    void ReloadAllValues() override;
    void SaveAllValues() override;

    /**
     * Like [SaveAllValues] but forces the overlay control_data block to be
     * written even for per-game configs that don't have one yet. Used by the
     * explicit overlay save path on the Java side: the user has actually
     * edited the layout, so this is the trigger to materialise the per-game
     * overlay block.
     */
    void SaveAllValuesForcingOverlay();

    void ReadAndroidControlPlayerValues(std::size_t player_index);
    void SaveAndroidControlPlayerValues(std::size_t player_index);

protected:
    void ReadAndroidPlayerValues(std::size_t player_index);
    void ReadAndroidControlValues();
    void ReadAndroidValues();
    void ReadAndroidUIValues();
    void ReadDriverValues();
    void ReadOverlayValues();
    void ReadHidbusValues() override {}
    void ReadDebugControlValues() override {}
    void ReadPathValues() override;
    void ReadShortcutValues() override {}
    void ReadUIValues() override;
    void ReadUIGamelistValues() override {}
    void ReadUILayoutValues() override {}
    void ReadMultiplayerValues() override {}

    void SaveAndroidPlayerValues(std::size_t player_index);
    void SaveAndroidControlValues();
    void SaveAndroidValues();
    void SaveAndroidUIValues();
    void SaveDriverValues();
    void SaveOverlayValues(bool forceWriteControlData = false);
    void SaveHidbusValues() override {}
    void SaveDebugControlValues() override {}
    void SavePathValues() override;
    void SaveShortcutValues() override {}
    void SaveUIValues() override;
    void SaveUIGamelistValues() override {}
    void SaveUILayoutValues() override {}
    void SaveMultiplayerValues() override {}

    std::vector<Settings::BasicSetting*>& FindRelevantList(Settings::Category category) override;
};
