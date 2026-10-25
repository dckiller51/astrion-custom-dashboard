// ---- Cards ---------------------------------------------------------------

const NAME_ENTITY_TYPES = ['source_select'];

// Every HA-backed card type used to have a free-text "Entity ID" input with
// live autocomplete over every entity Home Assistant has. Now it's a strict
// picker over the Devices page's catalog (dashboardData.haDevices, added at
// / -> "+ Add device" -> "Home Assistant Device") — a card can only ever
// reference a device that's actually in the catalog. Keep HA_DOMAIN_LABEL in
// sync with docs/js/devices-page.js's own copy if a new HA card type is ever added.
const HA_DOMAIN_LABEL_KEYS = {
  light: 'web_domain_light', switch: 'web_domain_switch', cover: 'web_domain_cover', climate: 'web_domain_climate',
  media_player: 'web_domain_media_player', camera: 'web_domain_camera', fan: 'web_domain_fan', vacuum: 'web_domain_vacuum',
  weather: 'web_domain_weather', select: 'web_domain_select'
};
const haDomainLabel = (domain) => (HA_DOMAIN_LABEL_KEYS[domain] ? I18N.t(HA_DOMAIN_LABEL_KEYS[domain]) : domain);

function haEntityFieldHtml(domain, fieldId, label) {
  const options = (dashboardData.haDevices || []).filter(d => d.domain === domain).map(d => ({ entityId: d.entityId, name: d.name }));
  // The built-in weather (1.3.2, js/weather.js) is a weather entity like the catalog's, without Home Assistant.
  const builtin = domain === 'weather' && typeof builtinWeatherOption === 'function' ? builtinWeatherOption() : null;
  if (builtin) options.unshift({ entityId: builtin[0], name: builtin[1] });
  if (!options.length) {
    return `
      <label>${label}</label>
      <select id="${fieldId}" disabled><option value="">${I18N.t('web_card_none_in_the_catalog')}</option></select>
      <div class="hint">${I18N.t('web_card_no_catalog_device', haDomainLabel(domain))}</div>
      ${domain === 'weather' ? `<div class="hint">${I18N.t('web_weather_card_hint')}</div>` : ''}
    `;
  }
  return `
    <label>${label}</label>
    <select id="${fieldId}">
      <option value="">${I18N.t('web_card_select')}</option>
      ${options.map(d => `<option value="${d.entityId}">${d.name}</option>`).join('')}
    </select>
  `;
}

function updateCardFormInputs() {
  const type = document.getElementById('cardTypeSelect').value;
  const container = document.getElementById('dynamicCardInputs');

  if (NAME_ENTITY_TYPES.includes(type)) {
    container.innerHTML = `
      <label>${I18N.t('web_config_harmony_name_label')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_name_room')}">
      ${haEntityFieldHtml('media_player', 'optEntityId', haDomainLabel('media_player'))}
      ${iconFieldHtml('optIcon')}
    `;
  } else if (type === 'title') {
    container.innerHTML = `
      <label>${I18N.t('web_card_title')}</label><input type="text" id="optTitle" placeholder="${I18N.t('web_card_ph_name_room')}">
      <label>${I18N.t('web_card_subtitle_opt')}</label><input type="text" id="optSubtitle" placeholder="${I18N.t('web_card_ph_subtitle')}">
      <label>${I18N.t('web_card_alignment')}</label>
      <select id="optTitleAlignment">
        <option value="start">${I18N.t('web_card_align_start')}</option>
        <option value="center">${I18N.t('web_card_align_center')}</option>
        <option value="end">${I18N.t('web_card_align_end')}</option>
        <option value="justify">${I18N.t('web_card_align_justify')}</option>
      </select>
      ${iconFieldHtml('optTitleIcon')}
      <label class="inline-check"><input type="checkbox" id="optTitleDivider"> ${I18N.t('web_card_title_divider')}</label>
      <label>${I18N.t('web_card_title_color_label')}</label><input type="text" id="optTitleColor" placeholder="#7FB3C4">
      <div class="hint">${I18N.t('web_card_title_hint')}</div>
    `;
  } else if (type === 'switch') {
    container.innerHTML = `
      <label>${I18N.t('web_config_harmony_name_label')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_name_room')}">
      ${haEntityFieldHtml('switch', 'optEntityId', haDomainLabel('switch'))}
      <label>${I18N.t('web_card_switch_on_color')}</label>${colorFieldHtml('optOnColor', '', '#FF2E5A46')}
      <label>${I18N.t('web_card_switch_icon')}</label>
      <select id="optSwitchIconKind" onchange="onSwitchIconKindChange()">
        <option value="power">${I18N.t('web_card_switch_icon_power')}</option>
        <option value="heater">${I18N.t('web_card_switch_icon_heater')}</option>
        <option value="fan">${I18N.t('web_card_switch_icon_fan')}</option>
        <option value="bulb">${I18N.t('web_card_switch_icon_bulb')}</option>
        <option value="png">${I18N.t('web_card_switch_icon_png')}</option>
      </select>
      <div id="switchPngIcon" style="display:none">${iconFieldHtml('optIcon')}</div>
    `;
  } else if (type === 'cover') {
    container.innerHTML = `
      <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_cover_name')}">
      ${haEntityFieldHtml('cover', 'optEntityId', haDomainLabel('cover'))}
      ${iconFieldHtml('optIcon')}
      <label>${I18N.t('web_card_layout')}</label>
      <select id="optCoverLayout">
        <option value="default">${I18N.t('web_card_cover_layout_default')}</option>
        <option value="horizontal">${I18N.t('web_card_cover_layout_horizontal')}</option>
        <option value="vertical">${I18N.t('web_card_cover_layout_vertical')}</option>
      </select>
      <label>${I18N.t('web_card_controls_mushroom_cover')}</label>
      <div class="hint-row">
        <label class="inline-check"><input type="checkbox" id="optCoverCtrlButtons" checked> ${I18N.t('web_card_cover_ctrl_buttons')}</label>
        <label class="inline-check"><input type="checkbox" id="optCoverCtrlPosition"> ${I18N.t('web_card_cover_ctrl_position')}</label>
        <label class="inline-check"><input type="checkbox" id="optCoverCtrlTilt"> ${I18N.t('web_card_cover_ctrl_tilt')}</label>
      </div>
      <div class="hint">${I18N.t('web_card_cover_hint')}</div>
    `;
  } else if (type === 'select') {
    container.innerHTML = `
      <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_select_name')}">
      ${haEntityFieldHtml('select', 'optEntityId', haDomainLabel('select'))}
      <label>${I18N.t('web_card_layout')}</label>
      <select id="optSelectLayout" onchange="onSelectLayoutChange()">
        <option value="default">${I18N.t('web_card_select_layout_default')}</option>
        <option value="horizontal">${I18N.t('web_card_select_layout_horizontal')}</option>
        <option value="vertical">${I18N.t('web_card_select_layout_vertical')}</option>
        <option value="dropdown">${I18N.t('web_card_select_layout_dropdown')}</option>
      </select>
      <div id="selectIconColorRow"><label>${I18N.t('web_card_select_icon_color')}</label>${colorFieldHtml('optSelectIconColor', '', '#6EA8FE')}</div>
      <label>${I18N.t('web_card_select_font_size')}</label>
      <select id="optSelectFontSize">
        <option value="">${I18N.t('web_card_select_font_auto')}</option>
        ${[12, 14, 16, 18, 20, 24, 28].map(n => `<option value="${n}">${n}</option>`).join('')}
      </select>
      <div class="hint">${I18N.t('web_card_select_hint')}</div>
    `;
  } else if (type === 'light') {
    container.innerHTML = `
      <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_kitchen')}">
      ${haEntityFieldHtml('light', 'optEntityId', haDomainLabel('light'))}
      <label>${I18N.t('web_card_layout')}</label>
      <select id="optLightLayout">
        <option value="default">${I18N.t('web_card_light_layout_default')}</option>
        <option value="horizontal">${I18N.t('web_card_light_layout_horizontal')}</option>
        <option value="vertical">${I18N.t('web_card_light_layout_vertical')}</option>
      </select>
      <label><input type="checkbox" id="optLightUseColor"> ${I18N.t('web_card_light_use_color')}</label>
      <label><input type="checkbox" id="optLightShowBrightness" checked> ${I18N.t('web_card_light_show_brightness')}</label>
      <label>${I18N.t('web_card_controls_mushroom_light')}</label>
      <div class="hint-row">
        <label class="inline-check"><input type="checkbox" id="optLightCtrlBrightness" checked> ${I18N.t('web_card_light_ctrl_brightness')}</label>
        <label class="inline-check"><input type="checkbox" id="optLightCtrlColorTemp"> ${I18N.t('web_card_light_ctrl_color_temp')}</label>
        <label class="inline-check"><input type="checkbox" id="optLightCtrlColor"> ${I18N.t('web_card_light_ctrl_color')}</label>
      </div>
      <label><input type="checkbox" id="optLightCollapsible"> ${I18N.t('web_card_light_collapsible')}</label>
      <div class="hint">${I18N.t('web_card_light_hint')}</div>
    `;
  } else if (type === 'media_player') {
    container.innerHTML = `
      <label>${I18N.t('web_card_media_name_label')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_media_name')}">
      ${haEntityFieldHtml('media_player', 'optEntityId', haDomainLabel('media_player'))}
      <label>${I18N.t('web_card_variant')}</label>
      <select id="optMediaVariant">
        <option value="compact">${I18N.t('web_card_media_variant_compact')}</option>
        <option value="full">${I18N.t('web_card_media_variant_full')}</option>
      </select>
      <label><input type="checkbox" id="optMediaUseInfo" checked> ${I18N.t('web_card_media_use_info')}</label>
      <label><input type="checkbox" id="optMediaShowVolume"> ${I18N.t('web_card_media_show_volume')}</label>
      <label>${I18N.t('web_card_media_transport_controls')}</label>
      <div class="hint-row">
        <label class="inline-check"><input type="checkbox" id="optMediaCtrlOnOff"> ${I18N.t('web_card_media_ctrl_power')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaCtrlShuffle"> ${I18N.t('web_card_media_ctrl_shuffle')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaCtrlPrevious" checked> ${I18N.t('web_card_media_ctrl_previous')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaCtrlPlayPause" checked> ${I18N.t('web_card_media_ctrl_play_pause')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaCtrlNext" checked> ${I18N.t('web_card_media_ctrl_next')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaCtrlRepeat"> ${I18N.t('web_card_media_ctrl_repeat')}</label>
      </div>
      <label>${I18N.t('web_card_media_volume_controls')}</label>
      <div class="hint-row">
        <label class="inline-check"><input type="checkbox" id="optMediaVolMute" checked> ${I18N.t('web_card_media_vol_mute')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaVolButtons" checked> ${I18N.t('web_card_media_vol_buttons')}</label>
        <label class="inline-check"><input type="checkbox" id="optMediaVolSet"> ${I18N.t('web_card_media_vol_slider')}</label>
      </div>
      <div class="hint">${I18N.t('web_card_media_hint')}</div>
      <div id="mediaTopButtonsField" style="display:none">
        <label>${I18N.t('web_card_media_top_buttons_label')}</label>
        <textarea id="optMediaTopButtons" rows="3" placeholder='[{"name":"Group","service":"media_player.join","entity_id":"media_player.salon","data":{"group_members":["media_player.cuisine"]}}]'>[]</textarea>
        <div class="hint">${I18N.t('web_card_media_top_buttons_hint')}</div>
      </div>
      <div id="mediaArtworkField" style="display:none">
        <div style="display:flex; gap:8px;">
          <div style="flex:1">
            <label>${I18N.t('web_card_artwork_fit_full_variant_only')}</label>
            <select id="optMediaArtworkFit">
              <option value="cover">${I18N.t('web_card_cover_crop_to_fill_default')}</option>
              <option value="contain">${I18N.t('web_card_contain_show_the_whole_image')}</option>
            </select>
          </div>
          <div style="flex:1">
            <label>${I18N.t('web_card_artwork_shape')}</label>
            <select id="optMediaArtworkRatio">
              <option value="square">${I18N.t('web_card_square_ish_default')}</option>
              <option value="portrait">${I18N.t('web_card_portrait_2_3_movie_posters')}</option>
            </select>
          </div>
        </div>
        <div class="hint">${I18N.t('web_card_movie_tv_artwork_is_usually_portrait')}</div>
      </div>
    `;
    document.getElementById('optMediaVariant').addEventListener('change', updateMediaTopButtonsVisibility);
    updateMediaTopButtonsVisibility();
  } else if (type === 'camera') {
    container.innerHTML = `
      <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_camera_name')}">
      ${haEntityFieldHtml('camera', 'optEntityId', haDomainLabel('camera'))}
      <label>${I18N.t('climate_hvac_caption')}</label>
      <select id="optCameraMode">
        <option value="stream">${I18N.t('web_card_camera_mode_stream')}</option>
        <option value="snapshot">${I18N.t('web_card_camera_mode_snapshot')}</option>
      </select>
      <label>${I18N.t('web_card_camera_interval')}</label><input type="number" id="optCameraInterval" value="2" min="1" max="60">
      <label>${I18N.t('web_card_camera_aspect')}</label>
      <select id="optCameraAspect">
        <option value="1.7778">${I18N.t('web_card_aspect_16_9')}</option>
        <option value="1.3333">${I18N.t('web_card_aspect_4_3')}</option>
        <option value="1">${I18N.t('web_card_aspect_1_1')}</option>
        <option value="0.75">${I18N.t('web_card_aspect_3_4')}</option>
      </select>
      <label>${I18N.t('web_card_camera_fit')}</label>
      <select id="optCameraFit">
        <option value="cover">${I18N.t('web_card_fit_cover')}</option>
        <option value="contain">${I18N.t('web_card_fit_contain')}</option>
      </select>
      <div class="hint">${I18N.t('web_card_camera_hint')}</div>
    `;
  } else if (type === 'fan') {
    container.innerHTML = `
      <label>${I18N.t('web_config_harmony_name_label')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_fan_name')}">
      ${haEntityFieldHtml('fan', 'optEntityId', haDomainLabel('fan'))}
      <label>${I18N.t('web_card_layout')}</label>
      <select id="optFanStyle">
        <option value="auto">${I18N.t('web_card_fan_style_auto')}</option>
        <option value="simple">${I18N.t('web_card_fan_style_simple')}</option>
        <option value="step">${I18N.t('web_card_fan_style_step')}</option>
        <option value="full">${I18N.t('web_card_fan_style_full')}</option>
      </select>
      <label>${I18N.t('web_card_fan_preset_modes')}</label><input type="text" id="optFanPresetModes" placeholder="${I18N.t('web_card_ph_fan_presets')}">
      <div id="fanStepWrap">
        <label>${I18N.t('web_card_fan_step')}</label><input type="number" id="optFanStep" value="20" min="1" max="100">
      </div>
      <label><input type="checkbox" id="optFanShowCaptions" checked> ${I18N.t('web_card_fan_show_captions')}</label>
      <div class="hint">${I18N.t('web_card_fan_hint')}</div>
    `;
    document.getElementById('optFanStyle').addEventListener('change', updateFanStepVisibility);
    updateFanStepVisibility();
  } else if (type === 'climate') {
    container.innerHTML = `
      <label>${I18N.t('web_config_harmony_name_label')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_climate_name')}">
      ${haEntityFieldHtml('climate', 'optEntityId', haDomainLabel('climate'))}
      <label>${I18N.t('web_card_climate_hvac_modes')}</label><input type="text" id="optHvacModes" placeholder="heat_cool,cool">
      <label>${I18N.t('web_card_climate_hvac_display')}</label>
      <select id="optHvacModeStyle">
        <option value="icons">${I18N.t('web_card_display_icon')}</option>
        <option value="label">${I18N.t('web_card_display_label')}</option>
      </select>
      <label>${I18N.t('web_card_climate_fan_modes')}</label><input type="text" id="optFanModes" placeholder="low,medium,high,auto">
      <label>${I18N.t('web_card_climate_fan_display')}</label>
      <select id="optFanModeStyle">
        <option value="label">${I18N.t('web_card_display_label')}</option>
        <option value="icons">${I18N.t('web_card_display_icon')}</option>
      </select>
      <label>${I18N.t('web_card_climate_swing_modes')}</label><input type="text" id="optSwingModes" placeholder="stop,swing">
      <label>${I18N.t('web_card_climate_swing_display')}</label>
      <select id="optSwingModeStyle">
        <option value="label">${I18N.t('web_card_display_label')}</option>
        <option value="icons">${I18N.t('web_card_display_icon')}</option>
      </select>
      <div class="hint">${I18N.t('web_card_climate_hint')}</div>
      <label><input type="checkbox" id="optShowCaptions" checked> ${I18N.t('web_card_climate_show_captions')}</label>
    `;
  } else if (type === 'button_grid' || type === 'scene_grid') {
    container.innerHTML = `
      <label>${I18N.t('web_card_columns')}</label><input type="number" id="optColumns" value="2" min="1">
      <label>${I18N.t('web_card_icon_position')}</label>
      <select id="optIconPosition">
        <option value="top">${I18N.t('web_card_top_above_label')}</option>
        <option value="bottom">${I18N.t('web_card_bottom_below_label')}</option>
        <option value="left">${I18N.t('web_card_left_beside_label')}</option>
        <option value="right">${I18N.t('web_card_right_beside_label')}</option>
      </select>
      ${type === 'scene_grid' ? `<div class="hint">${I18N.t('web_card_only_affects_tiles_that_show_both')}</div>` : ''}
      ${type === 'scene_grid' ? `<label><input type="checkbox" id="optShowLabels" checked> ${I18N.t('web_card_grid_show_labels')}</label><label><input type="checkbox" id="optIconFill"> ${I18N.t('web_card_grid_icon_fill')}</label>` : ''}
      <label>${I18N.t('web_card_grid_tile_height')}</label><input type="number" id="optTileHeight" min="30" max="300" placeholder="${I18N.t(type === 'scene_grid' ? 'web_card_ph_tile_height' : 'web_card_ph_button_height')}">
      <label>${I18N.t('web_card_grid_font_size')}</label><input type="number" id="optFontSize" min="8" max="60" placeholder="${I18N.t('web_card_ph_font_size')}">
      <label class="inline-check"><input type="checkbox" id="optCenterLastRow"> ${I18N.t('web_card_grid_center_last_row')}</label>
      <div id="gridItemsList"></div>
      <div class="section-box" style="margin-top:8px">
        <label>${type === 'button_grid' ? I18N.t('web_card_grid_button_name') : I18N.t('web_card_grid_scene_name')}</label><input type="text" id="giName" placeholder="${type === 'button_grid' ? I18N.t('web_card_ph_button_name') : I18N.t('web_card_ph_scene_name')}">
        ${type === 'button_grid' ? `
          <label>${I18N.t('web_card_service_domain_service_optional_or_combine')}</label><input type="text" id="giService" placeholder="${I18N.t('web_card_ph_service')}">
          <label>${I18N.t('web_card_entity_id_optional')}</label><input type="text" id="giEntityId" placeholder="${I18N.t('web_card_ph_media_tv')}">
          <label>${I18N.t('web_card_grid_data_label')}</label><input type="text" id="giData" placeholder='{"media_content_type":"app"}'>
          <div class="divider" style="margin:12px 0"></div>
          <label>${I18N.t('web_card_harmony_action_optional_or_and_combine')}</label>
          <select id="giHarmonyMode" onchange="onGiHarmonyModeChange()">
            <option value="">${I18N.t('web_none')}</option>
            <option value="activity">${I18N.t('web_card_harmony_activity')}</option>
            <option value="command">${I18N.t('web_card_harmony_device_command')}</option>
          </select>
          <div id="giHarmonyPicker"></div>
          <label>${I18N.t('web_card_ir_device_command_sends_locally_no')}</label>
          <select id="giIrDevice" onchange="onGiIrDeviceChange()">
            <option value="">${I18N.t('web_none')}</option>
            ${(dashboardData.irDevices || []).map(d => `<option value="${d.id}">${d.name}</option>`).join('')}
          </select>
          <input type="text" id="giIrCommand" list="giIrCommandHints" placeholder="${I18N.t('web_card_ph_ir_command')}">
          <datalist id="giIrCommandHints"></datalist>
          <label>${I18N.t('web_card_ir_delay')}</label><input type="number" id="giIrDelay" min="0" step="50" placeholder="300">
          <div class="hint">${I18N.t('web_card_ir_sequence_hint')}</div>
          ${(dashboardData.irDevices || []).length === 0 ? `<div class="hint">${I18N.t('web_card_no_ir_devices_yet_add_one')}</div>` : ''}
          <label class="inline-check"><input type="checkbox" id="giClosePopup"> ${I18N.t('web_card_close_the_open_popup_after_this')}</label>
          <div class="hint">${I18N.t('web_card_for_a_button_placed_inside_a')}</div>
          <div class="divider" style="margin:12px 0"></div>
          <label>${I18N.t('web_card_state_entity_optional_highlights_this_button')}</label><input type="text" id="giStateEntity" placeholder="${I18N.t('web_card_e_g_input_select_living_room')}">
          <label>${I18N.t('web_card_target_state_optional_comma_separated_for')}</label><input type="text" id="giStateValue" placeholder="e.g., TV">
          <label>${I18N.t('web_card_active_color_optional_hex_background_while')}</label>${colorFieldHtml('giActiveColor', '', '#FF2A4954')}
          <label class="inline-check"><input type="checkbox" id="giActiveBorder"> ${I18N.t('web_card_accent_border_while_active')}</label>
          <div class="hint">${I18N.t('web_card_visually_highlights_this_button_while_state')}</div>
        ` : `
          <label>${I18N.t('web_card_grid_scene_entity')}</label><input type="text" id="giEntityId" placeholder="${I18N.t('web_card_ph_scene_entity')}">
          <label>${I18N.t('web_card_grid_page')}</label><input type="text" id="giPage" placeholder="${I18N.t('web_card_ph_page')}">
          <select id="giPageMode">
            <option value="page">${I18N.t('web_card_as_a_full_page_default')}</option>
            <option value="popup">${I18N.t('web_card_as_a_popup_over_the_current')}</option>
          </select>
          <div class="hint">${I18N.t('web_card_popup_size_position_come_from_that')}</div>
          <label class="inline-check"><input type="checkbox" id="giClosePopup"> ${I18N.t('web_card_close_the_open_popup_after_this_2')}</label>
          <div class="hint">${I18N.t('web_card_for_a_tile_placed_inside_a')}</div>
          <label>${I18N.t('web_card_grid_harmony_action')}</label>
          <select id="giHarmonyMode" onchange="onGiHarmonyModeChange()">
            <option value="">${I18N.t('web_none')}</option>
            <option value="activity">${I18N.t('web_card_harmony_activity')}</option>
            <option value="command">${I18N.t('web_card_harmony_device_command')}</option>
          </select>
          <div id="giHarmonyPicker"></div>
          <label>${I18N.t('web_card_grid_ir')}</label>
          <select id="giIrDevice" onchange="onGiIrDeviceChange()">
            <option value="">${I18N.t('web_none')}</option>
            ${(dashboardData.irDevices || []).map(d => `<option value="${d.id}">${d.name}</option>`).join('')}
          </select>
          <input type="text" id="giIrCommand" list="giIrCommandHints" placeholder="${I18N.t('web_card_ph_ir_command')}">
          <datalist id="giIrCommandHints"></datalist>
          <label>${I18N.t('web_card_ir_delay')}</label><input type="number" id="giIrDelay" min="0" step="50" placeholder="300">
          <div class="hint">${I18N.t('web_card_ir_sequence_hint')}</div>
          ${(dashboardData.irDevices || []).length === 0 ? `<div class="hint">${I18N.t('web_card_no_ir_devices_yet_add_one')}</div>` : ''}
          <label>${I18N.t('web_card_grid_composed_activity')}</label>
          <select id="giActivityRef">
            <option value="">${I18N.t('web_none')}</option>
            ${(dashboardData.activities || []).map(a => `<option value="${a.id}">${a.name} (${a.room})</option>`).join('')}
          </select>
          ${(dashboardData.activities || []).length === 0 ? `<div class="hint">${I18N.t('web_card_grid_no_activities_hint')}</div>` : `<div class="hint">${I18N.t('web_card_saving_this_tile_sets_this_as')}</div>`}
          <label>${I18N.t('web_card_grid_color')}</label>${colorFieldHtml('giColor', '', '#66009688')}
          <div class="divider" style="margin:12px 0"></div>
          <label>${I18N.t('web_card_state_entity_optional_highlights_this_tile')}</label><input type="text" id="giStateEntity" placeholder="${I18N.t('web_card_ph_light')}">
          <label>${I18N.t('web_card_target_state_optional_comma_separated_for')}</label><input type="text" id="giStateValue" placeholder="e.g., on">
          <label>${I18N.t('web_card_active_color_optional_hex_background_while')}</label>${colorFieldHtml('giActiveColor', '', '#FF2A4954')}
          <label class="inline-check"><input type="checkbox" id="giActiveBorder"> ${I18N.t('web_card_accent_border_while_active')}</label>
          <div class="hint">${I18N.t('web_card_independent_of_track_as_activity_below')}</div>
          <div class="divider" style="margin:12px 0"></div>
          <label><input type="checkbox" id="giTrack" onchange="onGiTrackChange()"> ${I18N.t('web_card_grid_track')}</label>
          <div class="hint">${I18N.t('web_card_grid_track_hint')}</div>
          <div id="giRoomField" style="display:none">
            <label>${I18N.t('web_card_room')}</label><input type="text" id="giRoom" placeholder="${I18N.t('web_card_ph_name_room')}">
            <label>${I18N.t('web_card_grid_devices_label')}</label>
            <input type="text" id="giDevices" placeholder="${I18N.t('web_card_ph_device_ids')}">
            <div class="hint">${I18N.t('web_card_grid_devices_hint')}</div>
            <label>${I18N.t('web_activity_now_playing_label')}</label>
            <input type="text" id="giNowPlaying" list="giNowPlayingList" placeholder="media_player.apple_tv_salon">
            <datalist id="giNowPlayingList">${(typeof dockEntityOptions === 'function' ? dockEntityOptions('media_player') : []).map(([id, name]) => `<option value="${id}">${name}</option>`).join('')}</datalist>
            <div class="hint">${I18N.t('web_card_grid_now_playing_hint')}</div>
          </div>
        `}
        <div class="divider" style="margin:12px 0"></div>
        <label>${I18N.t('web_card_turn_off_a_room_s_activity')}</label>
        <input type="text" id="giStopActivity" list="giStopRooms" placeholder="${I18N.t('web_hotkey_stop_room_ph')}">
        <datalist id="giStopRooms">${activityRoomOptionsHtml()}</datalist>
        <div class="hint">${I18N.t('web_card_stops_whatever_activity_is_running_in')}</div>
        <label>${I18N.t('web_card_long_press_action_optional_json_fired')}</label>
        <textarea id="giLongPress" rows="2" placeholder='{"service":"script.astrion_bed_lights_off"}'></textarea>
        <div class="hint">${I18N.t('web_card_long_press_hint', type)}</div>
        ${iconFieldHtml('giIcon')}
        <button type="button" class="secondary" onclick="addGridItem('${type}')" id="giSubmitBtn" data-add-key="${type === 'button_grid' ? 'web_card_grid_add_button' : 'web_card_grid_add_scene'}">${type === 'button_grid' ? I18N.t('web_card_grid_add_button') : I18N.t('web_card_grid_add_scene')}</button>
        <button type="button" class="secondary" onclick="cancelGridItemEdit()" id="giCancelBtn" style="display:none">${I18N.t('web_cancel_edit')}</button>
        <button type="button" class="secondary" onclick="addGridSpacer('${type}')">${I18N.t('web_card_grid_add_spacer')}</button>
        <div class="hint">${I18N.t('web_card_grid_spacer_hint')}</div>
      </div>
      <div class="hint">${type === 'button_grid' ? I18N.t('web_card_grid_button_list_hint') : I18N.t('web_card_grid_scene_list_hint')}</div>
    `;
    window._pendingGridItems = window._pendingGridItems || [];
    renderGridItemsList(type);
  } else if (type === 'apple_tv_remote') {
    container.innerHTML = `
      <div id="atvSourceBox" style="display:none">
        <label>${I18N.t('web_card_control_via')}</label>
        <select id="atvSource" onchange="onAtvSourceChange()">
          <option value="direct">${I18N.t('web_card_apple_tv_direct_no_home_assistant')}</option>
          <option value="harmony">${I18N.t('web_harmony_label_hub')}</option>
        </select>
        <div id="atvDirectPicker"><label>${I18N.t('web_atv_label')}</label><select id="atvDirectSelect"></select></div>
      </div>
      <div id="atvHarmonyPicker"></div>
      <label>${I18N.t('web_card_layout')}</label>
      <select id="atvLayout" onchange="onAtvLayoutChange()">
        <option value="classic">${I18N.t('web_card_classic_trackpad_buttons_play_pause')}</option>
        <option value="compact">${I18N.t('web_card_compact_remote_first_now_playing_strip')}</option>
        <option value="full">${I18N.t('web_card_full_artwork_seek_bar_10_s')}</option>
      </select>
      <div id="atvRichBox" style="display:none">
        <label class="inline-check"><input type="checkbox" id="atvShowArtwork" checked> ${I18N.t('web_card_show_artwork')}</label>
        <label class="inline-check"><input type="checkbox" id="atvShowProgress" checked> ${I18N.t('web_card_show_title_progress_bar')}</label>
        <label class="inline-check"><input type="checkbox" id="atvShowSkip" checked> ${I18N.t('web_card_10_s_buttons_full_and_hold')}</label>
        <div class="hint">${I18N.t('web_card_compact_unticking_both_artwork_and_progress')}</div>
      </div>
      <label>${I18N.t('web_card_now_playing_apps_entity_optional')}</label>
      <select id="atvEntity" onchange="refreshAtvSourceHints()">${atvEntityOptionsHtml()}</select>
      <div class="hint">${I18N.t('web_card_automatic_uses_the_directly_paired_apple')}</div>
      <label>${I18N.t('web_card_buttons_below_the_trackpad')}</label>
      <div id="atvButtonsBox">${atvButtonsCheckboxesHtml()}</div>
      <div class="hint">${I18N.t('web_card_full_layout_the_first_2_buttons')}</div>
      <label>${I18N.t('web_card_apps_launched_with_media_player_select')}</label>
      <div id="atvAppsList"></div>
      <datalist id="atvSourceHints"></datalist>
      <button type="button" class="secondary" onclick="addAtvApp()">${I18N.t('web_card_add_an_app')}</button>
      <div class="hint">${I18N.t('web_card_app_is_the_name_as_the')}</div>`;
    window._pendingAtvApps = [];
    renderAtvAppsList();
    renderAppleTvHarmonyFields();
    initAtvSource();
  } else if (type === 'tv_remote') {
    container.innerHTML = tvRemoteFormHtml();
    initTvRemoteForm();
  } else if (type === 'xbox') {
    container.innerHTML = xboxFormHtml();
    initXboxForm();
  } else if (type === 'speaker_group') {
    container.innerHTML = speakerGroupFormHtml();
    window._pendingSpeakers = [];
    window._sgExtra = {};
    renderSgSpeakers();
  } else if (type === 'picture_elements') {
    container.innerHTML = pictureElementsFormHtml();
    initPictureElementsForm();
  } else if (type === 'row') {
    container.innerHTML = rowFormHtml();
    initRowForm();
  } else if (type === 'clock_weather') {
    container.innerHTML = `
      ${haEntityFieldHtml('weather', 'optEntityId', haDomainLabel('weather'))}
      <label>${I18N.t('web_card_time_format')}</label>
      <select id="optTimeFormat">
        <option value="12">${I18N.t('web_card_time_12h')}</option>
        <option value="24">${I18N.t('web_card_time_24h')}</option>
      </select>
      <label>${I18N.t('web_card_forecast_rows')}</label><input type="number" id="optForecastRows" value="4" min="0" max="10">
      <label>${I18N.t('web_card_calendar_entity')}</label><input type="text" id="optCalendarEntity" placeholder="${I18N.t('web_card_ph_calendar')}">
      <div class="hint">${I18N.t('web_card_clock_weather_hint')}</div>
    `;
  } else if (type === 'vacuum') {
    container.innerHTML = `
      <label>${I18N.t('web_card_name_optional_friendly')}</label><input type="text" id="optName" placeholder="${I18N.t('web_card_ph_vacuum_name')}">
      ${haEntityFieldHtml('vacuum', 'optEntityId', haDomainLabel('vacuum'))}
      <label>${I18N.t('web_card_map_image')}</label><input type="text" id="optMapImage" placeholder="${I18N.t('web_card_ph_map_image')}">
      <label>${I18N.t('web_card_map_rotation')}</label><input type="number" id="optMapRotation" value="0" step="90">
      <label>${I18N.t('web_card_map_height')}</label><input type="number" id="optMapHeight" value="200" min="0">
      <div id="vacuumRoomsList"></div>
      <div class="section-box" style="margin-top:8px">
        <label>${I18N.t('web_card_room_name')}</label><input type="text" id="vrName" placeholder="${I18N.t('web_card_ph_kitchen')}">
        <label>${I18N.t('web_card_room_segment_id')}</label><input type="number" id="vrId" placeholder="${I18N.t('web_card_ph_room_id')}">
        <button type="button" class="secondary" onclick="addVacuumRoom()">${I18N.t('web_card_vacuum_add_room')}</button>
      </div>
      <div class="hint">${I18N.t('web_card_vacuum_hint')}</div>

      <label style="margin-top:10px">${I18N.t('web_card_vacuum_room_clean_label')}</label>
      <input type="text" id="optRoomCleanDomain" placeholder="${I18N.t('web_card_ph_room_clean_domain')}">
      <input type="text" id="optRoomCleanService" placeholder="${I18N.t('web_card_ph_room_clean_service')}" style="margin-top:6px">
      <input type="text" id="optRoomCleanParameter" placeholder="${I18N.t('web_card_ph_room_clean_field')}" style="margin-top:6px">
      <div class="hint">${I18N.t('web_card_vacuum_room_clean_hint')}</div>
    `;
    window._pendingVacuumRooms = window._pendingVacuumRooms || [];
    renderVacuumRoomsList();
  } else if (type === 'monitor') {
    container.innerHTML = `
      <label>${I18N.t('web_card_title')}</label><input type="text" id="optMonitorTitle" placeholder="${I18N.t('web_card_monitor_title_ph')}">
      <label>${I18N.t('web_card_layout')}</label>
      <select id="optMonitorLayout">
        <option value="">${I18N.t('web_card_monitor_layout_horizontal')}</option>
        <option value="vertical">${I18N.t('web_card_monitor_layout_vertical')}</option>
      </select>
      <label class="inline-check"><input type="checkbox" id="optMonitorMarquee"> ${I18N.t('web_card_monitor_marquee')}</label>
      <div class="hint">${I18N.t('web_card_monitor_hint')}</div>
      <label>${I18N.t('web_card_monitor_rows')}</label>
      <div id="monitorRowsList"></div>
      <button type="button" class="secondary" onclick="addMonitorRow()">${I18N.t('web_card_monitor_add_row')}</button>
    `;
    window._pendingMonitorRows = window._pendingMonitorRows || [];
    renderMonitorRows();
  } else if (type === 'plex') {
    container.innerHTML = `
      <label>${I18N.t('web_card_plex_host')}</label><input type="text" id="optPlexHost" placeholder="${I18N.t('web_card_ph_plex_host')}">
      <label>${I18N.t('web_card_plex_token')}</label><input type="text" id="optPlexToken" placeholder="${I18N.t('web_card_ph_plex_token')}">
      <div class="hint">${I18N.t('web_card_plex_token_hint')} <a href="https://github.com/dckiller51/astrion-custom-dashboard/wiki/Card-Plex#finding-your-token" target="_blank" rel="noopener">${I18N.t('web_card_plex_token_help')}</a></div>
      <label>${I18N.t('web_card_plex_media_entity')}</label><input type="text" id="optPlexMediaEntity" placeholder="${I18N.t('web_card_ph_media_tv')}">
      <label>${I18N.t('web_card_plex_source')}</label><input type="text" id="optPlexSource" value="Plex" placeholder="${I18N.t('web_card_plex')}">
      <label>${I18N.t('web_card_plex_play_entity')}</label><input type="text" id="optPlexPlayEntity" placeholder="${I18N.t('web_card_ph_plex_play_entity')}">
      <label>${I18N.t('web_card_plex_play_type')}</label>
      <select id="optPlexPlayContentType">
        <option value="video">${I18N.t('web_card_plex_type_video')}</option>
        <option value="url">${I18N.t('web_card_plex_type_url')}</option>
      </select>
      <div class="hint">${I18N.t('web_card_plex_client_hint')}</div>
      <label>${I18N.t('web_card_plex_rows')}</label>
      <div class="hint-row">
        <label class="inline-check"><input type="checkbox" id="optPlexShowOnDeck" checked> ${I18N.t('web_card_plex_row_on_deck')}</label>
        <label class="inline-check"><input type="checkbox" id="optPlexShowMovies" checked> ${I18N.t('plex_recently_added_movies')}</label>
        <label class="inline-check"><input type="checkbox" id="optPlexShowShows" checked> ${I18N.t('plex_recently_added_tv')}</label>
      </div>
      <label>${I18N.t('web_card_plex_items_per_row')}</label><input type="number" id="optPlexItemsPerRow" value="12" min="1" max="30">
      <div class="hint">${I18N.t('web_card_plex_rows_hint')}</div>
    `;
  } else {
    // Advanced / custom: raw options JSON, and free type name if "custom"
    container.innerHTML = `
      ${type === 'custom' ? `<label>${I18N.t('web_card_type_string')}</label><input type="text" id="optCustomType" placeholder="${I18N.t('web_card_ph_custom_type')}">` : ''}
      <label>${I18N.t('web_card_options_json')}</label>
      <textarea id="optRawJson" rows="4" placeholder='{"entity_id": "..."}'>{}</textarea>
      <div class="hint">${I18N.t('web_card_custom_hint')}</div>
    `;
  }

  // Attach live entity autocomplete to whichever entity_id fields this card
  // type created. Only attaches when /ha-states data is available (device mode
  // + HA connected); otherwise the inputs stay plain text fields. optEntityId
  // itself is excluded — it's now a strict <select> over the Devices catalog
  // (see haEntityFieldHtml above), not a free-text field to autocomplete.
  ['optRemoteEntity', 'optMediaEntity', 'optMuteEntity',
    'optCalendarEntity', 'optMapImage', 'giEntityId',
    'optPlexMediaEntity', 'optPlexPlayEntity'].forEach(id => {
    const el = document.getElementById(id);
    if (!el) return;
    const dom = id === 'optRemoteEntity' ? 'remote'
      : id === 'optMediaEntity' ? 'media_player'
      : id === 'optMuteEntity' ? 'remote'
      : id === 'optCalendarEntity' ? 'calendar'
      : id === 'optMapImage' ? 'image'
      : id === 'optPlexMediaEntity' ? 'media_player'
      : id === 'optPlexPlayEntity' ? 'media_player'
      : null; // giEntityId — any domain (scene.*, script.*, media_player.*, …)
    attachEntityAutocomplete(el, dom);
  });
}

// media_player card: top_buttons only make sense on the "full" variant
// (the compact tile has no room for them) — hide the field otherwise.
function updateMediaTopButtonsVisibility() {
  const variantEl = document.getElementById('optMediaVariant');
  const field = document.getElementById('mediaTopButtonsField');
  const artworkField = document.getElementById('mediaArtworkField');
  if (!variantEl || !field) return;
  field.style.display = variantEl.value === 'full' ? '' : 'none';
  if (artworkField) artworkField.style.display = variantEl.value === 'full' ? '' : 'none';
}

// fan card: percentage step only applies to simple/full layouts — the
// "step" style uses HA's increase_speed/decrease_speed services, so there's
// no numeric step to configure.
function updateFanStepVisibility() {
  const styleEl = document.getElementById('optFanStyle');
  const wrap = document.getElementById('fanStepWrap');
  if (!styleEl || !wrap) return;
  wrap.style.display = styleEl.value === 'step' ? 'none' : '';
}

let editingGridItem = null; // index of the button/scene being edited within _pendingGridItems, or null

function onGiTrackChange() {
  const checked = document.getElementById('giTrack').checked;
  document.getElementById('giRoomField').style.display = checked ? '' : 'none';
}

/**
 * Refreshes the giIrCommand <datalist> for whichever IR device is
 * currently picked — command ids are only actually *known* here for
 * inline devices (their commands map is right there in dashboardData).
 * For an ir-database *reference* device, the real command list only
 * exists on the phone at runtime (the sdcard file); the best this builder
 * can do is suggest whatever ids were typed into "known command ids" while
 * creating that device — saved as that device's own `commandHints` field
 * in dashboard.json (see saveIrDevice() in docs/js/devices-page.js) —
 * giIrCommand is a plain text input specifically so an unlisted id still
 * works fine, it's just not autocompleted.
 */
function onGiIrDeviceChange() {
  const deviceId = document.getElementById('giIrDevice').value;
  const datalist = document.getElementById('giIrCommandHints');
  const device = (dashboardData.irDevices || []).find(d => d.id === deviceId);
  if (!device) { datalist.innerHTML = ''; return; }
  const ids = device.commands ? Object.keys(device.commands) : (device.commandHints || []);
  datalist.innerHTML = ids.map(id => `<option value="${id}">`).join('');
}

/** Apple TVs paired directly in the Devices page (fetched fresh each time the form opens). */
async function loadNativeAppleTvs() {
  try {
    const res = await fetch('/devices-config');
    const data = await res.json();
    return Array.isArray(data.appleTvs) ? data.appleTvs : [];
  } catch (e) {
    return [];
  }
}

/** True when the Apple TV remote form is set to the direct (Companion) source. */
function atvDirectActive() {
  const box = document.getElementById('atvSourceBox');
  const source = document.getElementById('atvSource');
  return !!box && box.style.display !== 'none' && !!source && source.value === 'direct';
}

function onAtvSourceChange() {
  const direct = atvDirectActive();
  const directPicker = document.getElementById('atvDirectPicker');
  const harmonyPicker = document.getElementById('atvHarmonyPicker');
  if (directPicker) directPicker.style.display = direct ? '' : 'none';
  if (harmonyPicker) harmonyPicker.style.display = direct ? 'none' : '';
}

/** Shows the "Control via" choice only when at least one Apple TV is paired; otherwise the form is exactly as before (Harmony only). */
async function initAtvSource() {
  const tvs = await loadNativeAppleTvs();
  const box = document.getElementById('atvSourceBox');
  if (!box) return;
  if (!tvs.length) { box.style.display = 'none'; onAtvSourceChange(); return; }
  const select = document.getElementById('atvDirectSelect');
  select.innerHTML = '';
  tvs.forEach(tv => {
    const option = document.createElement('option');
    option.value = tv.entityId;
    option.textContent = `${tv.name} (${tv.entityId})`;
    select.appendChild(option);
  });
  box.style.display = '';
  document.getElementById('atvSource').value = 'direct';
  select.onchange = refreshAtvSourceHints;
  onAtvSourceChange();
  refreshAtvSourceHints();
}

// Keep in sync with AppleTvRemoteCard.kt's ExtraButtons.CATALOG (ids and default selection).
const ATV_EXTRA_BUTTONS = [
  { id: 'Menu', label: 'Menu', labelKey: 'web_atv_btn_menu' },
  { id: 'Home', label: 'Home', labelKey: 'web_atv_btn_home' },
  { id: 'SeekBackward', label: '−10 s' },
  { id: 'SeekForward', label: '+10 s' },
  { id: 'Rewind', label: 'Rewind ⏪', labelKey: 'web_atv_btn_rewind' },
  { id: 'FastForward', label: 'Fast forward ⏩', labelKey: 'web_atv_btn_fast_forward' },
  { id: 'Previous', label: 'Previous track', labelKey: 'web_atv_btn_previous' },
  { id: 'Next', label: 'Next track', labelKey: 'web_atv_btn_next' },
  { id: 'Siri', label: 'Siri', labelKey: 'web_atv_btn_siri' },
  { id: 'Screensaver', label: 'Screensaver', labelKey: 'web_atv_btn_screensaver' },
  { id: 'Guide', label: 'Guide', labelKey: 'web_atv_btn_guide' },
  { id: 'VolumeUp', label: 'Volume +', labelKey: 'web_atv_btn_volume_up' },
  { id: 'VolumeDown', label: 'Volume −', labelKey: 'web_atv_btn_volume_down' },
  { id: 'ControlCenter', label: 'Control Center', labelKey: 'web_atv_btn_control_center' }
];
const ATV_DEFAULT_BUTTONS = ['Menu', 'Home'];
// Per-layout defaults (AppleTvRemoteCard.kt: DEFAULT_IDS for classic and full, COMPACT_DEFAULT_IDS).
const ATV_LAYOUT_DEFAULT_BUTTONS = {
  classic: ['Menu', 'Home'],
  compact: ['Menu', 'Home', 'SeekBackward', 'SeekForward'],
  full: ['Menu', 'Home']
};

function onAtvLayoutChange() {
  const layout = document.getElementById('atvLayout').value;
  const rich = document.getElementById('atvRichBox');
  if (rich) rich.style.display = layout === 'classic' ? 'none' : '';
  // Swap the button selection to the new layout's default, unless it was customised.
  const current = selectedAtvButtons().join(',');
  const untouched = Object.values(ATV_LAYOUT_DEFAULT_BUTTONS).some(d => d.join(',') === current);
  const box = document.getElementById('atvButtonsBox');
  if (untouched && box) box.innerHTML = atvButtonsCheckboxesHtml(ATV_LAYOUT_DEFAULT_BUTTONS[layout]);
}

/**
 * Options for the now-playing entity dropdown: "Automatic" (empty value) first, then every
 * media_player from the device catalog and from the live /ha-states data, without duplicates.
 * [selected] is kept even when it is in neither list, so editing an older card never loses it.
 */
function atvEntityOptionsHtml(selected) {
  const seen = new Map();
  (dashboardData.haDevices || []).filter(d => d.domain === 'media_player')
    .forEach(d => seen.set(d.entityId, d.name || d.entityId));
  const states = (typeof haStates !== 'undefined' && haStates) ? haStates : {};
  Object.keys(states).filter(id => id.startsWith('media_player.')).forEach(id => {
    if (!seen.has(id)) seen.set(id, (states[id] && states[id].friendly_name) || id);
  });
  if (selected && !seen.has(selected)) seen.set(selected, I18N.t('web_not_found', selected));
  const items = [...seen.entries()].sort((a, b) => String(a[1]).localeCompare(String(b[1])));
  return `<option value="">${I18N.t('web_card_atv_entity_auto')}</option>` +
    items.map(([id, name]) =>
      `<option value="${escapeAttr(id)}" ${id === selected ? 'selected' : ''}>${escapeAttr(name)} (${escapeAttr(id)})</option>`
    ).join('');
}

/** The media_player whose source_list feeds the app suggestions: the explicit entity, else the direct Apple TV. */
function atvSourceEntity() {
  const explicit = (document.getElementById('atvEntity') || {}).value;
  if (explicit && explicit.trim()) return explicit.trim();
  return atvDirectActive() ? document.getElementById('atvDirectSelect').value : '';
}

async function refreshAtvSourceHints() {
  const datalist = document.getElementById('atvSourceHints');
  if (!datalist) return;
  let states = (typeof haStates !== 'undefined' && haStates) ? haStates : null;
  if (!states) {
    try {
      const res = await fetch('/ha-states');
      const data = await res.json();
      states = data && data.states ? data.states : null;
    } catch (e) {
      states = null;
    }
  }
  const entity = atvSourceEntity();
  const st = states && entity ? states[entity] : null;
  const sources = st && st.attributes && Array.isArray(st.attributes.source_list) ? st.attributes.source_list : [];
  datalist.innerHTML = sources.map(src => `<option value="${escapeAttr(src)}">`).join('');
}

function escapeAttr(v) {
  return String(v == null ? '' : v).replace(/&/g, '&amp;').replace(/"/g, '&quot;').replace(/</g, '&lt;');
}

function atvAppThumbUrl(icon) {
  if (!icon) return null;
  return /^https?:\/\//.test(icon) ? icon : iconUrl(icon);
}

function renderAtvAppsList() {
  const list = document.getElementById('atvAppsList');
  if (!list) return;
  const apps = window._pendingAtvApps || [];
  if (!apps.length) {
    list.innerHTML = `<div class="hint">${I18N.t('web_card_atv_no_apps')}</div>`;
    return;
  }
  list.innerHTML = apps.map((app, i) => {
    const thumb = atvAppThumbUrl(app.icon);
    const color = /^#[0-9a-fA-F]{6}$/.test(app.color || '') ? app.color : '#3a3a40';
    return `
    <div class="atv-app-row">
      <div class="atv-app-fields">
        <div>
          <label for="atvAppSource${i}">${I18N.t('web_card_atv_app_n', i + 1)}</label>
          <input type="text" id="atvAppSource${i}" list="atvSourceHints" placeholder="${I18N.t('web_card_atv_e_g_netflix')}" value="${escapeAttr(app.source)}" oninput="updateAtvApp(${i}, 'source', this.value)">
        </div>
        <div>
          <label for="atvAppLabel${i}">${I18N.t('web_card_atv_label_optional')}</label>
          <input type="text" id="atvAppLabel${i}" placeholder="${I18N.t('web_card_atv_same_as_app')}" value="${escapeAttr(app.label)}" oninput="updateAtvApp(${i}, 'label', this.value)">
        </div>
      </div>
      <label for="atvAppIcon${i}">${I18N.t('web_card_atv_icon_optional')}</label>
      <div class="icon-field-row">
        <input type="text" id="atvAppIcon${i}" placeholder="${I18N.t('web_card_atv_sdcard_astrion_icons_xxx_png_or')}" value="${escapeAttr(app.icon)}" oninput="updateAtvApp(${i}, 'icon', this.value); updateIconThumb('atvAppIcon${i}')">
        <img class="icon-field-thumb${thumb ? ' shown' : ''}" id="atvAppIcon${i}Thumb" alt="" ${thumb ? `src="${escapeAttr(thumb)}"` : ''} onerror="this.classList.remove('shown')">
        <button type="button" class="secondary" onclick="openIconPicker('atvAppIcon${i}')">${I18N.t('web_icon_choose')}</button>
      </div>
      <div class="atv-app-actions">
        <label class="atv-app-color">${I18N.t('web_card_atv_color')} <input type="color" value="${color}" onchange="updateAtvApp(${i}, 'color', this.value)"></label>
        <span class="atv-app-spacer"></span>
        <button type="button" class="secondary" title="${I18N.t('web_preview_move_up')}" onclick="moveAtvApp(${i}, -1)" ${i === 0 ? 'disabled' : ''}>↑</button>
        <button type="button" class="secondary" title="${I18N.t('web_preview_move_down')}" onclick="moveAtvApp(${i}, 1)" ${i === apps.length - 1 ? 'disabled' : ''}>↓</button>
        <button type="button" class="danger" onclick="removeAtvApp(${i})">${I18N.t('web_config_harmony_remove_button')}</button>
      </div>
    </div>`;
  }).join('');
}

// The icon picker fills the icon input programmatically (no input event), so read the
// icon fields back from the page before anything re-renders or saves the list.
function syncAtvAppIcons() {
  (window._pendingAtvApps || []).forEach((app, i) => {
    const input = document.getElementById('atvAppIcon' + i);
    if (input) app.icon = input.value;
  });
}

function addAtvApp() {
  syncAtvAppIcons();
  window._pendingAtvApps = window._pendingAtvApps || [];
  window._pendingAtvApps.push({ source: '', label: '', icon: '', color: '' });
  renderAtvAppsList();
  refreshAtvSourceHints();
}

function updateAtvApp(i, key, value) {
  const app = (window._pendingAtvApps || [])[i];
  if (app) app[key] = value;
}

function moveAtvApp(i, delta) {
  syncAtvAppIcons();
  const apps = window._pendingAtvApps || [];
  const j = i + delta;
  if (j < 0 || j >= apps.length) return;
  [apps[i], apps[j]] = [apps[j], apps[i]];
  renderAtvAppsList();
}

function removeAtvApp(i) {
  syncAtvAppIcons();
  (window._pendingAtvApps || []).splice(i, 1);
  renderAtvAppsList();
}

/** Cleaned-up apps for the card's options: entries without an app name are dropped, empty fields omitted. */
function collectAtvApps() {
  syncAtvAppIcons();
  return (window._pendingAtvApps || [])
    .filter(a => a.source && a.source.trim())
    .map(a => {
      const out = { source: a.source.trim() };
      if (a.label && a.label.trim()) out.label = a.label.trim();
      if (a.icon && a.icon.trim()) out.icon = a.icon.trim();
      if (a.color && a.color.toLowerCase() !== '#3a3a40') out.color = a.color;
      return out;
    });
}

function atvButtonsCheckboxesHtml(selected) {
  const checked = selected && selected.length ? selected : ATV_DEFAULT_BUTTONS;
  return ATV_EXTRA_BUTTONS.map(b => `
    <label class="inline-check">
      <input type="checkbox" class="atv-button-check" value="${b.id}" ${checked.includes(b.id) ? 'checked' : ''}>
      ${b.labelKey ? I18N.t(b.labelKey) : b.label}
    </label>`).join('');
}

function selectedAtvButtons() {
  return [...document.querySelectorAll('.atv-button-check:checked')].map(el => el.value);
}

function renderAppleTvHarmonyFields() {
  const container = document.getElementById('atvHarmonyPicker');
  if (!container) return;
  if (harmonyAvailable) {
    renderHarmonyHubSelect(container, 'device', 'atv');
  } else {
    container.innerHTML = `<label>${I18N.t('web_card_atv_device_id')}</label><input type="text" id="optDeviceId" placeholder="${I18N.t('web_card_ph_harmony_device_id')}">`;
  }
}

async function fillAppleTvHarmonyFields(o) {
  renderAppleTvHarmonyFields();
  const layout = ['compact', 'full'].includes(o.layout) ? o.layout : 'classic';
  document.getElementById('atvLayout').value = layout;
  document.getElementById('atvRichBox').style.display = layout === 'classic' ? 'none' : '';
  document.getElementById('atvShowArtwork').checked = o.showArtwork !== false;
  document.getElementById('atvShowProgress').checked = o.showProgress !== false;
  document.getElementById('atvShowSkip').checked = o.showSkip !== false;
  document.getElementById('atvEntity').innerHTML = atvEntityOptionsHtml(o.entity || '');
  window._pendingAtvApps = JSON.parse(JSON.stringify(o.apps || [])).map(a => ({
    source: a.source || '', label: a.label || '', icon: a.icon || '', color: a.color || ''
  }));
  renderAtvAppsList();
  const buttonsBox = document.getElementById('atvButtonsBox');
  if (buttonsBox) buttonsBox.innerHTML = atvButtonsCheckboxesHtml(o.buttons && o.buttons.length ? o.buttons : ATV_LAYOUT_DEFAULT_BUTTONS[layout]);
  await initAtvSource();
  refreshAtvSourceHints();
  const box = document.getElementById('atvSourceBox');
  if (o.appleTv && box && box.style.display !== 'none') {
    document.getElementById('atvSource').value = 'direct';
    const select = document.getElementById('atvDirectSelect');
    if (![...select.options].some(opt => opt.value === o.appleTv)) {
      const missing = document.createElement('option'); // a card pointing at an Apple TV that is no longer paired
      missing.value = o.appleTv; missing.textContent = I18N.t('web_atv_not_paired', o.appleTv);
      select.appendChild(missing);
    }
    select.value = o.appleTv;
    onAtvSourceChange();
    return;
  }
  if (box && box.style.display !== 'none') {
    document.getElementById('atvSource').value = 'harmony';
    onAtvSourceChange();
  }
  if (harmonyAvailable) {
    document.getElementById('atvHub').value = o.hub || '';
    if (o.hub) {
      await onHarmonyHubChange('device', 'atv');
      document.getElementById('atvDeviceSelect').value = o.deviceId || '';
    }
  } else {
    document.getElementById('optDeviceId').value = o.deviceId || '';
  }
}

function onGiHarmonyModeChange() {
  const mode = document.getElementById('giHarmonyMode').value;
  const container = document.getElementById('giHarmonyPicker');
  if (!mode) { container.innerHTML = ''; return; }
  if (harmonyAvailable) {
    renderHarmonyHubSelect(container, mode, 'gi');
  } else if (mode === 'activity') {
    container.innerHTML = `<label>${I18N.t('web_card_harmony_activity_id')}</label><input type="text" id="giActivityId" placeholder="${I18N.t('web_card_ph_harmony_activity')}">`;
  } else {
    container.innerHTML = `
      <label>${I18N.t('web_card_harmony_device_id')}</label><input type="text" id="giHarmonyDevice" placeholder="${I18N.t('web_card_ph_harmony_device_id')}">
      <label>${I18N.t('web_card_harmony_command')}</label><input type="text" id="giHarmonyCommand" placeholder="${I18N.t('web_card_ph_harmony_command')}">
    `;
  }
}

function renderGridItemsList(type) {
  const list = document.getElementById('gridItemsList');
  if (!list) return;
  list.innerHTML = '';
  const items = window._pendingGridItems || [];
  items.forEach((item, i) => {
    const el = document.createElement('div');
    el.className = 'list-item';
    const upDisabled = i === 0 ? ' style="opacity:.3;pointer-events:none"' : '';
    const downDisabled = i === items.length - 1 ? ' style="opacity:.3;pointer-events:none"' : '';
    const label = item.spacer ? `<i style="color:#888">${I18N.t('web_card_grid_spacer')}</i>` : (item.name || I18N.t('web_unnamed'));
    const editBtn = item.spacer ? '' : `<span class="remove" style="color:#00E5FF" onclick="editGridItem('${type}', ${i})">✎</span> `;
    el.innerHTML = `<span>${label}</span><span>` +
      `<span class="remove"${upDisabled} onclick="moveGridItem('${type}', ${i}, -1)" title="${I18N.t('web_preview_move_up')}">↑</span> ` +
      `<span class="remove"${downDisabled} onclick="moveGridItem('${type}', ${i}, 1)" title="${I18N.t('web_preview_move_down')}">↓</span> ` +
      `${editBtn}<span class="remove" onclick="removeGridItem('${type}', ${i})">✕</span></span>`;
    list.appendChild(el);
  });
}

/** IR command field (1.3.7): one command, or several separated by commas sent in a row (`irCommands`). */
function fillGiIrCommands(item) {
  const cmds = Array.isArray(item.irCommands) ? item.irCommands.join(', ') : (item.irCommand || '');
  document.getElementById('giIrCommand').value = cmds;
  const delay = document.getElementById('giIrDelay');
  if (delay) delay.value = item.irDelayMs != null ? item.irDelayMs : '';
}

/** Writes irDevice + irCommand, or irCommands (+ irDelayMs) for "8, 0, 1"; false (after an alert) when a command is missing. */
function collectGiIrCommands(item) {
  const irDevice = document.getElementById('giIrDevice')?.value || '';
  const cmds = (document.getElementById('giIrCommand')?.value || '').split(',').map(c => c.trim()).filter(Boolean);
  if (!irDevice) return true;
  if (cmds.length === 0) { alert(I18N.t('web_card_alert_pick_ir_command')); return false; }
  item.irDevice = irDevice;
  if (cmds.length === 1) { item.irCommand = cmds[0]; return true; }
  item.irCommands = cmds;
  const delay = parseInt(document.getElementById('giIrDelay')?.value || '', 10);
  if (!isNaN(delay) && delay >= 0) item.irDelayMs = delay;
  return true;
}

function fillGridItemForm(type, item) {
  document.getElementById('giName').value = item.name || '';
  const longPressEl = document.getElementById('giLongPress');
  if (longPressEl) longPressEl.value = item.long_press ? JSON.stringify(item.long_press) : '';
  const stopEl = document.getElementById('giStopActivity');
  if (stopEl) stopEl.value = item.stopActivity || '';
  document.getElementById('giIcon').value = item.icon || '';
  updateIconThumb('giIcon');
  if (type === 'button_grid') {
    document.getElementById('giService').value = item.service || '';
    document.getElementById('giEntityId').value = item.entity_id || '';
    document.getElementById('giData').value = item.data ? JSON.stringify(item.data) : '';
    document.getElementById('giIrDevice').value = item.irDevice || '';
    fillGiIrCommands(item);
    document.getElementById('giClosePopup').checked = item.closePopup === true;
    document.getElementById('giStateEntity').value = item.state_entity || '';
    document.getElementById('giStateValue').value = Array.isArray(item.state_value) ? item.state_value.join(', ') : (item.state_value || '');
    setColorFieldValue('giActiveColor', item.active_color || '');
    document.getElementById('giActiveBorder').checked = item.active_border === true || typeof item.active_border === 'string';
  } else {
    document.getElementById('giEntityId').value = item.entity_id || '';
    document.getElementById('giPage').value = item.page || '';
    document.getElementById('giPageMode').value = item.pageMode === 'popup' ? 'popup' : 'page';
    document.getElementById('giClosePopup').checked = item.closePopup === true;
    if (item.irDevice) {
      document.getElementById('giIrDevice').value = item.irDevice;
      onGiIrDeviceChange();
      fillGiIrCommands(item);
    }
    const actRefSel = document.getElementById('giActivityRef');
    if (actRefSel) actRefSel.value = item.activity || '';
    setColorFieldValue('giColor', item.color || '');
    document.getElementById('giStateEntity').value = item.state_entity || '';
    document.getElementById('giStateValue').value = Array.isArray(item.state_value) ? item.state_value.join(', ') : (item.state_value || '');
    setColorFieldValue('giActiveColor', item.active_color || '');
    document.getElementById('giActiveBorder').checked = item.active_border === true || typeof item.active_border === 'string';
    document.getElementById('giTrack').checked = item.track === true;
    document.getElementById('giRoom').value = item.room || '';
    document.getElementById('giDevices').value = (item.devices || []).join(', ');
    document.getElementById('giNowPlaying').value = item.nowPlayingEntity || '';
    onGiTrackChange();
  }
}

/**
 * Restores a scene/button item's Harmony fields into the form when editing.
 * Deliberately does NOT default `hub` to "the first configured hub" when the
 * item has none — with more than one hub configured, guessing is exactly
 * the kind of silent wrong-hub bug that bit us before. An item saved before
 * `hub` was mandatory just shows the Hub select empty, forcing an explicit
 * pick before it can be saved again (see the validation in addGridItem()).
 */
async function fillGiHarmonySection(item) {
  const modeSel = document.getElementById('giHarmonyMode');
  if (!modeSel) return; // defensive — the select always exists for scene_grid/button_grid forms
  const mode = item.activityId ? 'activity' : (item.harmonyDevice && item.harmonyCommand) ? 'command' : '';
  modeSel.value = mode;
  onGiHarmonyModeChange();
  if (!mode) return;
  if (harmonyAvailable) {
    document.getElementById('giHub').value = item.hub || '';
    if (item.hub) {
      await onHarmonyHubChange(mode, 'gi');
      if (mode === 'activity') {
        document.getElementById('giActivitySelect').value = item.activityId || '';
      } else {
        document.getElementById('giDeviceSelect').value = item.harmonyDevice || '';
        onHarmonyDeviceChange('gi');
        document.getElementById('giCommandSelect').value = item.harmonyCommand || '';
      }
    }
  } else if (mode === 'activity') {
    document.getElementById('giActivityId').value = item.activityId || '';
  } else {
    document.getElementById('giHarmonyDevice').value = item.harmonyDevice || '';
    document.getElementById('giHarmonyCommand').value = item.harmonyCommand || '';
  }
}

async function editGridItem(type, i) {
  editingGridItem = i;
  const item = window._pendingGridItems[i];
  fillGridItemForm(type, item);
  await fillGiHarmonySection(item);
  document.getElementById('giSubmitBtn').textContent = `Save ${type === 'button_grid' ? 'button' : 'scene'}`;
  document.getElementById('giCancelBtn').style.display = '';
}

function cancelGridItemEdit() {
  editingGridItem = null;
  document.getElementById('giName').value = '';
  document.getElementById('giIcon').value = '';
  updateIconThumb('giIcon');
  ['giService', 'giEntityId', 'giData', 'giPage', 'giIrDevice', 'giIrCommand', 'giIrDelay', 'giActivityRef', 'giStateEntity', 'giStateValue', 'giLongPress', 'giStopActivity'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.value = '';
  });
  const pageModeEl = document.getElementById('giPageMode');
  if (pageModeEl) pageModeEl.value = 'page';
  const closePopupEl = document.getElementById('giClosePopup');
  if (closePopupEl) closePopupEl.checked = false;
  setColorFieldValue('giColor', '');
  setColorFieldValue('giActiveColor', '');
  const activeBorderEl = document.getElementById('giActiveBorder');
  if (activeBorderEl) activeBorderEl.checked = false;
  const trackEl = document.getElementById('giTrack');
  if (trackEl) { trackEl.checked = false; document.getElementById('giRoom').value = ''; document.getElementById('giDevices').value = ''; document.getElementById('giNowPlaying').value = ''; onGiTrackChange(); }
  const harmonyModeSel = document.getElementById('giHarmonyMode');
  if (harmonyModeSel) { harmonyModeSel.value = ''; onGiHarmonyModeChange(); }
  const btn = document.getElementById('giSubmitBtn');
  if (btn && btn.dataset.addKey) btn.textContent = I18N.t(btn.dataset.addKey);
  document.getElementById('giCancelBtn').style.display = 'none';
}

function addGridItem(type) {
  const name = document.getElementById('giName').value.trim();
  const icon = document.getElementById('giIcon').value.trim();
  let item = { name };
  if (icon) item.icon = icon;
  const stopActivity = (document.getElementById('giStopActivity')?.value || '').trim();
  if (stopActivity) item.stopActivity = stopActivity;
  // Optional separate long-press action — parsed up front so a typo is
  // reported before anything else, and written last (below) so it never
  // counts toward the tap action's own "needs an action" validation.
  let longPress = null;
  const rawLongPress = (document.getElementById('giLongPress')?.value || '').trim();
  if (rawLongPress) {
    try { longPress = JSON.parse(rawLongPress); } catch (e) { alert(I18N.t('web_card_err_long_press_json')); return; }
    if (!longPress || typeof longPress !== 'object' || Array.isArray(longPress)) { alert(I18N.t('web_card_err_long_press_object')); return; }
    if (Object.keys(longPress).length === 0) longPress = null;
  }
  if (type === 'button_grid') {
    const service = document.getElementById('giService').value.trim();
    if (service) item.service = service;
    const entityId = document.getElementById('giEntityId').value.trim();
    if (entityId) item.entity_id = entityId;
    const rawData = document.getElementById('giData').value.trim();
    if (rawData) {
      try { item.data = JSON.parse(rawData); } catch (e) { alert(I18N.t('web_card_alert_data_json')); return; }
    }
    if (!collectGiIrCommands(item)) return;
    if (document.getElementById('giClosePopup')?.checked) item.closePopup = true;

    const harmonyMode = document.getElementById('giHarmonyMode')?.value || '';
    if (harmonyMode === 'activity') {
      if (harmonyAvailable) {
        const hub = document.getElementById('giHub').value.trim();
        const activityId = document.getElementById('giActivitySelect').value.trim();
        if (!hub || !activityId) { alert(I18N.t('web_card_alert_pick_hub_activity')); return; }
        item.hub = hub;
        item.activityId = activityId;
      } else {
        const activityId = document.getElementById('giActivityId').value.trim();
        if (activityId) item.activityId = activityId;
      }
    } else if (harmonyMode === 'command') {
      if (harmonyAvailable) {
        const hub = document.getElementById('giHub').value.trim();
        const device = document.getElementById('giDeviceSelect').value.trim();
        const command = document.getElementById('giCommandSelect').value.trim();
        if (!hub || !device || !command) { alert(I18N.t('web_card_alert_pick_hub_device_command')); return; }
        item.hub = hub;
        item.harmonyDevice = device;
        item.harmonyCommand = command;
      } else {
        item.harmonyDevice = document.getElementById('giHarmonyDevice').value.trim();
        item.harmonyCommand = document.getElementById('giHarmonyCommand').value.trim();
      }
    }
    if (!item.service && !item.irDevice && !item.harmonyDevice && !item.activityId && !item.stopActivity) {
      alert(I18N.t('web_card_err_button_action'));
      return;
    }

    const stateEntity = document.getElementById('giStateEntity').value.trim();
    const stateValueRaw = document.getElementById('giStateValue').value.trim();
    const activeColor = colorFieldValue('giActiveColor');
    const activeBorder = document.getElementById('giActiveBorder').checked;
    if (stateEntity && !stateValueRaw) { alert(I18N.t('web_card_err_target_state')); return; }
    if (stateEntity) {
      item.state_entity = stateEntity;
      const values = stateValueRaw.split(',').map(s => s.trim()).filter(Boolean);
      item.state_value = values.length > 1 ? values : values[0];
      if (activeColor) item.active_color = activeColor;
      if (activeBorder) item.active_border = true;
    }
  } else {
    const entityId = document.getElementById('giEntityId').value.trim();
    const page = document.getElementById('giPage').value.trim();
    const pageMode = document.getElementById('giPageMode')?.value || 'page';
    const closePopup = document.getElementById('giClosePopup')?.checked || false;
    const activityRef = document.getElementById('giActivityRef')?.value || '';
    const color = colorFieldValue('giColor');
    if (entityId) item.entity_id = entityId;
    if (page) item.page = page;
    // Only written when the page opens as a popup — an absent pageMode
    // means "full page", same default as before this option existed.
    if (page && pageMode === 'popup') item.pageMode = 'popup';
    if (closePopup) item.closePopup = true;
    if (!collectGiIrCommands(item)) return;
    if (activityRef) {
      item.activity = activityRef;
      // Binding volume keys AND the Activity's own "page" here (not in the
      // Activity itself, which was removed — see activities.js's
      // writeVolumeHotkeysForActivity doc) because a card is the one place
      // this Activity's page is actually unambiguous: an explicit override
      // on this tile (`page`, set right above) if there is one, otherwise
      // the page this card is on right now.
      //
      // activity.page specifically also drives the "Active Activities"
      // overlay in Dashboard.kt — tapping a running activity there
      // navigates via activity.page (only tappable when it's set) — so
      // this isn't just for volume hotkeys, an Activity placed on zero
      // cards legitimately has no page and that overlay entry just won't
      // be tappable, same as it never was for an Activity with no page set
      // before this catalog redesign.
      const activity = (dashboardData.activities || []).find(a => a.id === activityRef);
      const targetPage = item.page || (dashboardData.pages[currentActivePage] || {}).name;
      if (activity && targetPage) {
        activity.page = targetPage;
        writeVolumeHotkeysForActivity(activity, targetPage);
      }
    }
    if (color) item.color = color;

    const harmonyMode = document.getElementById('giHarmonyMode')?.value || '';
    if (harmonyMode === 'activity') {
      if (harmonyAvailable) {
        const hub = document.getElementById('giHub').value.trim();
        const activityId = document.getElementById('giActivitySelect').value.trim();
        if (!hub || !activityId) { alert(I18N.t('web_card_alert_pick_hub_activity')); return; }
        item.hub = hub;
        item.activityId = activityId;
      } else {
        const activityId = document.getElementById('giActivityId').value.trim();
        if (activityId) item.activityId = activityId;
      }
    } else if (harmonyMode === 'command') {
      if (harmonyAvailable) {
        const hub = document.getElementById('giHub').value.trim();
        const device = document.getElementById('giDeviceSelect').value.trim();
        const command = document.getElementById('giCommandSelect').value.trim();
        if (!hub || !device || !command) { alert(I18N.t('web_card_alert_pick_hub_device_command')); return; }
        item.hub = hub;
        item.harmonyDevice = device;
        item.harmonyCommand = command;
      } else {
        item.harmonyDevice = document.getElementById('giHarmonyDevice').value.trim();
        item.harmonyCommand = document.getElementById('giHarmonyCommand').value.trim();
      }
    }

    const track = document.getElementById('giTrack')?.checked || false;
    if (track) {
      const room = document.getElementById('giRoom').value.trim();
      if (!room) { alert(I18N.t('web_card_alert_track_needs_room')); return; }
      item.track = true;
      item.room = room;
      const devices = document.getElementById('giDevices').value.split(',').map(s => s.trim()).filter(Boolean);
      if (devices.length) item.devices = devices;
      const nowPlaying = document.getElementById('giNowPlaying').value.trim();
      if (nowPlaying) item.nowPlayingEntity = nowPlaying;
    }

    const stateEntity = document.getElementById('giStateEntity').value.trim();
    const stateValueRaw = document.getElementById('giStateValue').value.trim();
    const activeColor = colorFieldValue('giActiveColor');
    const activeBorder = document.getElementById('giActiveBorder').checked;
    if (stateEntity && !stateValueRaw) { alert(I18N.t('web_card_err_target_state')); return; }
    if (stateEntity) {
      item.state_entity = stateEntity;
      const values = stateValueRaw.split(',').map(s => s.trim()).filter(Boolean);
      item.state_value = values.length > 1 ? values : values[0];
      if (activeColor) item.active_color = activeColor;
      if (activeBorder) item.active_border = true;
    }
  }
  if (longPress) item.long_press = longPress;
  window._pendingGridItems = window._pendingGridItems || [];
  if (editingGridItem !== null) {
    window._pendingGridItems[editingGridItem] = item;
  } else {
    window._pendingGridItems.push(item);
  }
  cancelGridItemEdit();
  renderGridItemsList(type);
}

/** tile_height, font_size and center_last_row (1.3.7), shared by button_grid and scene_grid. */
function collectGridSizeOptions(o) {
  const th = parseInt(document.getElementById('optTileHeight').value, 10);
  if (th > 0) o.tile_height = th;
  const fs = parseInt(document.getElementById('optFontSize').value, 10);
  if (fs > 0) o.font_size = fs;
  if (document.getElementById('optCenterLastRow').checked) o.center_last_row = true;
}

/** An empty cell (1.3.7) to place the other buttons, e.g. empty, 0, empty under 7 8 9. */
function addGridSpacer(type) {
  window._pendingGridItems = window._pendingGridItems || [];
  window._pendingGridItems.push({ spacer: true });
  renderGridItemsList(type);
}

function removeGridItem(type, i) {
  window._pendingGridItems.splice(i, 1);
  if (editingGridItem === i) cancelGridItemEdit();
  renderGridItemsList(type);
}

/**
 * Reorders a scene_grid/button_grid tile by swapping it with its neighbor
 * (dir -1 = move up/left, +1 = move down/right — the grid just wraps by
 * columns, so "up" and "left" are the same underlying array move). Keeps
 * `editingGridItem` pointed at the same item if it's mid-edit, so moving a
 * tile doesn't silently switch the edit form to a different one.
 */
function moveGridItem(type, i, dir) {
  const items = window._pendingGridItems || [];
  const j = i + dir;
  if (j < 0 || j >= items.length) return;
  [items[i], items[j]] = [items[j], items[i]];
  if (editingGridItem === i) editingGridItem = j;
  else if (editingGridItem === j) editingGridItem = i;
  renderGridItemsList(type);
}

// Fake example entity — used to preview a `climate` card that has no
// hvac_modes/fan_modes/swing_modes override, so the builder still shows a
// realistic result instead of an empty shell. Based on a real Daikin unit.

function renderVacuumRoomsList() {
  const list = document.getElementById('vacuumRoomsList');
  if (!list) return;
  list.innerHTML = '';
  (window._pendingVacuumRooms || []).forEach((room, i) => {
    const el = document.createElement('div');
    el.className = 'list-item';
    el.innerHTML = `<span>${room.name || I18N.t('web_unnamed')} — ${I18N.t('web_card_room_id_display', room.id)}</span><span class="remove" onclick="removeVacuumRoom(${i})">✕</span>`;
    list.appendChild(el);
  });
}

function addVacuumRoom() {
  const name = document.getElementById('vrName').value.trim();
  const idRaw = document.getElementById('vrId').value.trim();
  const id = parseInt(idRaw, 10);
  if (!name || isNaN(id)) { alert(I18N.t('web_card_alert_room_name_id')); return; }
  window._pendingVacuumRooms = window._pendingVacuumRooms || [];
  window._pendingVacuumRooms.push({ name, id });
  document.getElementById('vrName').value = '';
  document.getElementById('vrId').value = '';
  renderVacuumRoomsList();
}

function removeVacuumRoom(i) {
  window._pendingVacuumRooms.splice(i, 1);
  updateCardFormInputs();
}

// Opens the card dialog — the single entry point for both "+ Add card" and
// the ✎ edit icon on a card in the preview, same as clicking "+ ADD CARD" or
// a card's own edit action in the Home Assistant dashboard editor.
function openCardDialog(idx) {
  editingCard = idx;
  window._rowCtx = null;
  setRowOptionEnabled(true);
  const isNew = idx === null;
  document.getElementById('cardEditorTitle').textContent = isNew ? I18N.t('web_modal_add_card') : I18N.t('web_modal_edit_card');
  document.getElementById('addCardBtn').innerText = isNew ? I18N.t('web_modal_add_card_to_page') : I18N.t('web_save_changes');

  const select = document.getElementById('cardTypeSelect');
  if (isNew) {
    select.value = select.options[0].value;
    updateCardFormInputs();
  } else {
    const card = dashboardData.pages[currentActivePage].cards[idx];
    const known = Array.from(select.options).map(o => o.value).filter(v => v !== 'custom');
    select.value = known.includes(card.type) ? card.type : 'custom';
    updateCardFormInputs();
    fillCardForm(card);
  }

  document.getElementById('cardEditorModal').classList.add('open');
}

function editCard(idx) {
  openCardDialog(idx);
}

// Sets an HA entity <select>'s value safely — if entityId isn't one of the
// catalog options (an old dashboard from before this feature, or a card
// edited via raw JSON with an entity that was never added to the catalog),
// a select's .value setter just silently fails to select anything, and
// re-saving the form would then blank out the card's real entity_id. Inject
// a clearly-labeled extra option instead, so editing never loses data.
function setEntitySelectValue(elId, entityId) {
  const el = document.getElementById(elId);
  if (!el) return;
  if (!entityId) { el.value = ''; return; }
  if (!Array.from(el.options).some(o => o.value === entityId)) {
    const opt = document.createElement('option');
    opt.value = entityId;
    opt.textContent = I18N.t('web_not_in_catalog', entityId);
    el.insertBefore(opt, el.firstChild);
  }
  el.value = entityId;
}

function fillCardForm(card) {
  const type = card.type;
  const o = card.options || {};
  if (NAME_ENTITY_TYPES.includes(type)) {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optIcon').value = o.icon || '';
    updateIconThumb('optIcon');
  } else if (type === 'title') {
    document.getElementById('optTitle').value = o.title || '';
    document.getElementById('optSubtitle').value = o.subtitle || '';
    document.getElementById('optTitleAlignment').value = ['center', 'end', 'justify'].includes(o.alignment) ? o.alignment : 'start';
    document.getElementById('optTitleIcon').value = o.icon || '';
    updateIconThumb('optTitleIcon');
    document.getElementById('optTitleDivider').checked = o.divider === true;
    document.getElementById('optTitleColor').value = o.color || '';
  } else if (type === 'switch') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    setColorFieldValue('optOnColor', o.on_color || '');
    const swIcon = o.icon || '';
    const swKind = swIcon.includes('/') ? 'png'
      : ['heater', 'heat'].includes(swIcon) ? 'heater'
      : swIcon === 'fan' ? 'fan'
      : ['bulb', 'light'].includes(swIcon) ? 'bulb' : 'power';
    document.getElementById('optSwitchIconKind').value = swKind;
    document.getElementById('optIcon').value = swKind === 'png' ? swIcon : '';
    updateIconThumb('optIcon');
    onSwitchIconKindChange();
  } else if (type === 'cover') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optIcon').value = o.icon || '';
    updateIconThumb('optIcon');
    document.getElementById('optCoverLayout').value = ['horizontal', 'vertical'].includes(o.layout) ? o.layout : 'default';
    // Mirrors CoverCard.kt: if none of the 3 flags are set at all, the app
    // falls back to "buttons only" — reflect that same default here.
    const hasCoverCtrlOpts = ('show_buttons_control' in o) || ('show_position_control' in o) || ('show_tilt_position_control' in o);
    document.getElementById('optCoverCtrlButtons').checked = hasCoverCtrlOpts ? (o.show_buttons_control === true) : true;
    document.getElementById('optCoverCtrlPosition').checked = o.show_position_control === true;
    document.getElementById('optCoverCtrlTilt').checked = o.show_tilt_position_control === true;
  } else if (type === 'select') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    setColorFieldValue('optSelectIconColor', o.icon_color || '');
    document.getElementById('optSelectLayout').value = ['horizontal', 'vertical', 'dropdown'].includes(o.layout) ? o.layout : 'default';
    const fsSel = document.getElementById('optSelectFontSize');
    if (o.font_size != null && !Array.from(fsSel.options).some(op => op.value === String(o.font_size))) {
      fsSel.insertAdjacentHTML('beforeend', `<option value="${o.font_size}">${o.font_size}</option>`);
    }
    fsSel.value = o.font_size != null ? String(o.font_size) : '';
    onSelectLayoutChange();
  } else if (type === 'light') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optLightLayout').value = ['horizontal', 'vertical'].includes(o.layout) ? o.layout : 'default';
    document.getElementById('optLightUseColor').checked = o.use_light_color === true;
    document.getElementById('optLightShowBrightness').checked = o.show_brightness !== false;
    // Mirrors LightCard.kt: if none of the 3 flags are set at all, the app
    // falls back to "brightness control only" — reflect that same default here.
    const hasLightCtrlOpts = ('show_brightness_control' in o) || ('show_color_temp_control' in o) || ('show_color_control' in o);
    document.getElementById('optLightCtrlBrightness').checked = hasLightCtrlOpts ? (o.show_brightness_control === true) : true;
    document.getElementById('optLightCtrlColorTemp').checked = o.show_color_temp_control === true;
    document.getElementById('optLightCtrlColor').checked = o.show_color_control === true;
    document.getElementById('optLightCollapsible').checked = o.collapsible_controls === true;
  } else if (type === 'media_player') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optMediaVariant').value = o.variant === 'full' ? 'full' : 'compact';
    document.getElementById('optMediaUseInfo').checked = o.use_media_info !== false;
    document.getElementById('optMediaShowVolume').checked = o.show_volume_level === true;
    const mCtrls = (o.media_controls || 'previous,play_pause,next').split(',').map(s => s.trim());
    document.getElementById('optMediaCtrlOnOff').checked = mCtrls.includes('on_off');
    document.getElementById('optMediaCtrlShuffle').checked = mCtrls.includes('shuffle');
    document.getElementById('optMediaCtrlPrevious').checked = mCtrls.includes('previous');
    document.getElementById('optMediaCtrlPlayPause').checked = mCtrls.includes('play_pause');
    document.getElementById('optMediaCtrlNext').checked = mCtrls.includes('next');
    document.getElementById('optMediaCtrlRepeat').checked = mCtrls.includes('repeat');
    const vCtrls = (o.volume_controls ?? 'mute,buttons').split(',').map(s => s.trim());
    document.getElementById('optMediaVolMute').checked = vCtrls.includes('mute');
    document.getElementById('optMediaVolButtons').checked = vCtrls.includes('buttons');
    document.getElementById('optMediaVolSet').checked = vCtrls.includes('set');
    document.getElementById('optMediaTopButtons').value = JSON.stringify(o.top_buttons || [], null, 2);
    document.getElementById('optMediaArtworkFit').value = o.artwork_fit === 'contain' ? 'contain' : 'cover';
    document.getElementById('optMediaArtworkRatio').value = o.artwork_ratio === 'portrait' ? 'portrait' : 'square';
    updateMediaTopButtonsVisibility();
  } else if (type === 'camera') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optCameraMode').value = o.mode === 'snapshot' ? 'snapshot' : 'stream';
    document.getElementById('optCameraInterval').value = o.snapshot_interval ?? 2;
    // Snap the aspect dropdown to the stored value when it matches a preset;
    // an unusual custom aspect just shows the default here (the JSON keeps its
    // own value until the card is re-saved from this form).
    const aspSel = document.getElementById('optCameraAspect');
    aspSel.value = (o.aspect != null) ? String(o.aspect) : '1.7778';
    if (!aspSel.value) aspSel.value = '1.7778';
    document.getElementById('optCameraFit').value = o.fit === 'contain' ? 'contain' : 'cover';
  } else if (type === 'fan') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optFanStyle').value = ['simple', 'step', 'full'].includes(o.style) ? o.style : 'auto';
    document.getElementById('optFanPresetModes').value = (o.preset_modes || []).join(',');
    document.getElementById('optFanStep').value = o.step ?? 20;
    updateFanStepVisibility();
    document.getElementById('optFanShowCaptions').checked = o.show_captions !== false;
  } else if (type === 'climate') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optHvacModes').value = (o.hvac_modes || []).join(',');
    document.getElementById('optHvacModeStyle').value = o.hvac_mode_style === 'label' ? 'label' : 'icons';
    document.getElementById('optFanModes').value = (o.fan_modes || []).join(',');
    document.getElementById('optFanModeStyle').value = o.fan_mode_style === 'icons' ? 'icons' : 'label';
    document.getElementById('optSwingModes').value = (o.swing_modes || []).join(',');
    document.getElementById('optSwingModeStyle').value = o.swing_mode_style === 'icons' ? 'icons' : 'label';
    document.getElementById('optShowCaptions').checked = o.show_captions !== false;
  } else if (type === 'button_grid' || type === 'scene_grid') {
    document.getElementById('optColumns').value = o.columns || 2;
    document.getElementById('optIconPosition').value = ['top', 'bottom', 'left', 'right'].includes(o.iconPosition) ? o.iconPosition : 'top';
    if (type === 'scene_grid') {
      document.getElementById('optShowLabels').checked = o.show_labels !== false;
      document.getElementById('optIconFill').checked = o.icon_fill === true;
    }
    document.getElementById('optTileHeight').value = o.tile_height || '';
    document.getElementById('optFontSize').value = o.font_size || '';
    document.getElementById('optCenterLastRow').checked = o.center_last_row === true;
    window._pendingGridItems = JSON.parse(JSON.stringify(o.buttons || o.scenes || []));
    renderGridItemsList(type);
  } else if (type === 'apple_tv_remote') {
    fillAppleTvHarmonyFields(o);
  } else if (type === 'tv_remote') {
    fillTvRemoteForm(o);
  } else if (type === 'xbox') {
    fillXboxForm(o);
  } else if (type === 'speaker_group') {
    fillSpeakerGroupForm(o);
  } else if (type === 'picture_elements') {
    fillPictureElementsForm(o);
  } else if (type === 'row') {
    fillRowForm(o);
  } else if (type === 'clock_weather') {
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optTimeFormat').value = (o.time_format === 24) ? '24' : '12';
    document.getElementById('optForecastRows').value = (o.forecast_rows ?? 4);
    document.getElementById('optCalendarEntity').value = o.calendar_entity || '';
  } else if (type === 'vacuum') {
    document.getElementById('optName').value = o.name || '';
    setEntitySelectValue('optEntityId', o.entity_id);
    document.getElementById('optMapImage').value = o.map_image || '';
    document.getElementById('optMapRotation').value = o.map_rotation ?? 0;
    document.getElementById('optMapHeight').value = o.map_height ?? 200;
    window._pendingVacuumRooms = JSON.parse(JSON.stringify(o.rooms || []));
    renderVacuumRoomsList();
    const rca = o.room_clean_action || {};
    document.getElementById('optRoomCleanDomain').value = rca.domain || '';
    document.getElementById('optRoomCleanService').value = rca.service || '';
    document.getElementById('optRoomCleanParameter').value = rca.parameter || '';
  } else if (type === 'monitor') {
    document.getElementById('optMonitorTitle').value = o.title || '';
    document.getElementById('optMonitorLayout').value = o.layout === 'vertical' ? 'vertical' : '';
    document.getElementById('optMonitorMarquee').checked = o.marquee === true;
    window._pendingMonitorRows = (Array.isArray(o.entities) ? o.entities : []).map(r => ({ ...r }));
    // Any other option set by hand in dashboard.json survives an edit through this form.
    window._monitorExtraOptions = Object.fromEntries(Object.entries(o).filter(([k]) => !['title', 'layout', 'marquee', 'entities'].includes(k)));
    renderMonitorRows();
  } else if (type === 'plex') {
    document.getElementById('optPlexHost').value = o.host || '';
    document.getElementById('optPlexToken').value = o.token || '';
    document.getElementById('optPlexMediaEntity').value = o.media_entity || '';
    document.getElementById('optPlexSource').value = o.source || 'Plex';
    document.getElementById('optPlexPlayEntity').value = o.play_entity || '';
    document.getElementById('optPlexPlayContentType').value = o.play_content_type || 'video';
    document.getElementById('optPlexShowOnDeck').checked = o.show_on_deck !== false;
    document.getElementById('optPlexShowMovies').checked = o.show_recently_added_movies !== false;
    document.getElementById('optPlexShowShows').checked = o.show_recently_added_shows !== false;
    document.getElementById('optPlexItemsPerRow').value = o.items_per_row ?? 12;
  } else {
    const customField = document.getElementById('optCustomType');
    if (customField) customField.value = type;
    document.getElementById('optRawJson').value = JSON.stringify(o, null, 2);
  }
}

function cancelCardEdit() {
  // Closing a row's child card goes back to the row instead.
  if (window._rowCtx && window._rowCtx.active) { returnToRowForm(); return; }
  editingCard = null;
  resetPendingCardForms();
  window._rowCtx = null;
  setRowOptionEnabled(true);
  document.getElementById('addCardBtn').innerText = I18N.t('web_modal_add_card_to_page');
  document.getElementById('cardEditorModal').classList.remove('open');
}

// Reorder helpers — used by the ↑/↓ buttons and by drag-and-drop in
// preview.js's enhanceCardControls().
function moveCard(idx, direction) {
  const cards = dashboardData.pages[currentActivePage].cards;
  const target = idx + direction;
  if (target < 0 || target >= cards.length) return;
  [cards[idx], cards[target]] = [cards[target], cards[idx]];
  renderPreview(); updateJsonOutput();
}

function reorderCard(fromIdx, toIdx) {
  const cards = dashboardData.pages[currentActivePage].cards;
  if (fromIdx < 0 || fromIdx >= cards.length || toIdx < 0 || toIdx >= cards.length) return;
  const [moved] = cards.splice(fromIdx, 1);
  cards.splice(toIdx, 0, moved);
  renderPreview(); updateJsonOutput();
}

function addCardToPage() {
  const pageIndex = currentActivePage;
  const type = document.getElementById('cardTypeSelect').value;
  let newCard = { type: type, options: {} };

  if (NAME_ENTITY_TYPES.includes(type)) {
    newCard.options.name = document.getElementById('optName').value || 'Component';
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'domain.entity';
    const icon = document.getElementById('optIcon').value.trim();
    if (icon) newCard.options.icon = icon;
  } else if (type === 'title') {
    const title = document.getElementById('optTitle').value.trim();
    const subtitle = document.getElementById('optSubtitle').value.trim();
    if (!title && !subtitle) { alert(I18N.t('web_card_alert_title_required')); return; }
    if (title) newCard.options.title = title;
    if (subtitle) newCard.options.subtitle = subtitle;
    const alignment = document.getElementById('optTitleAlignment').value;
    if (alignment !== 'start') newCard.options.alignment = alignment;
    const titleIcon = document.getElementById('optTitleIcon').value.trim();
    if (titleIcon) newCard.options.icon = titleIcon;
    if (document.getElementById('optTitleDivider').checked) newCard.options.divider = true;
    const titleColor = document.getElementById('optTitleColor').value.trim();
    if (titleColor) newCard.options.color = titleColor;
  } else if (type === 'switch') {
    newCard.options.name = document.getElementById('optName').value || 'Switch';
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'switch.entity';
    const onColor = colorFieldValue('optOnColor');
    if (onColor) newCard.options.on_color = onColor;
    const swKind = document.getElementById('optSwitchIconKind').value;
    const icon = swKind === 'png' ? document.getElementById('optIcon').value.trim() : (swKind === 'power' ? '' : swKind);
    if (icon) newCard.options.icon = icon;
  } else if (type === 'cover') {
    const coverName = document.getElementById('optName').value.trim();
    if (coverName) newCard.options.name = coverName;
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'cover.entity';
    const coverIcon = document.getElementById('optIcon').value.trim();
    if (coverIcon) newCard.options.icon = coverIcon;
    const coverLayout = document.getElementById('optCoverLayout').value;
    if (coverLayout !== 'default') newCard.options.layout = coverLayout;
    // Written explicitly (even when checked/default) so the app's "any flag
    // present => only what's set shows" logic is unambiguous once the
    // builder has touched this card.
    newCard.options.show_buttons_control = document.getElementById('optCoverCtrlButtons').checked;
    newCard.options.show_position_control = document.getElementById('optCoverCtrlPosition').checked;
    newCard.options.show_tilt_position_control = document.getElementById('optCoverCtrlTilt').checked;
  } else if (type === 'select') {
    const selectName = document.getElementById('optName').value.trim();
    if (selectName) newCard.options.name = selectName;
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'input_select.entity';
    const selectLayout = document.getElementById('optSelectLayout').value;
    if (selectLayout !== 'default') newCard.options.layout = selectLayout;
    const selectIconColor = colorFieldValue('optSelectIconColor');
    if (selectIconColor && selectLayout !== 'dropdown') newCard.options.icon_color = selectIconColor;
    const selectFont = parseInt(document.getElementById('optSelectFontSize').value, 10);
    if (!isNaN(selectFont)) newCard.options.font_size = selectFont;
  } else if (type === 'light') {
    const lightName = document.getElementById('optName').value.trim();
    if (lightName) newCard.options.name = lightName;
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'light.entity';
    const lightLayout = document.getElementById('optLightLayout').value;
    if (lightLayout !== 'default') newCard.options.layout = lightLayout;
    if (document.getElementById('optLightUseColor').checked) newCard.options.use_light_color = true;
    if (!document.getElementById('optLightShowBrightness').checked) newCard.options.show_brightness = false;
    // Written explicitly (even when checked/default) so the app's "any flag
    // present => only what's set shows" logic is unambiguous once the
    // builder has touched this card.
    newCard.options.show_brightness_control = document.getElementById('optLightCtrlBrightness').checked;
    newCard.options.show_color_temp_control = document.getElementById('optLightCtrlColorTemp').checked;
    newCard.options.show_color_control = document.getElementById('optLightCtrlColor').checked;
    if (document.getElementById('optLightCollapsible').checked) newCard.options.collapsible_controls = true;
  } else if (type === 'media_player') {
    const mediaName = document.getElementById('optName').value.trim();
    if (mediaName) newCard.options.name = mediaName;
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'media_player.entity';
    const mediaVariant = document.getElementById('optMediaVariant').value;
    if (mediaVariant === 'full') newCard.options.variant = 'full';
    if (!document.getElementById('optMediaUseInfo').checked) newCard.options.use_media_info = false;
    if (document.getElementById('optMediaShowVolume').checked) newCard.options.show_volume_level = true;
    const mediaControls = [];
    if (document.getElementById('optMediaCtrlOnOff').checked) mediaControls.push('on_off');
    if (document.getElementById('optMediaCtrlShuffle').checked) mediaControls.push('shuffle');
    if (document.getElementById('optMediaCtrlPrevious').checked) mediaControls.push('previous');
    if (document.getElementById('optMediaCtrlPlayPause').checked) mediaControls.push('play_pause');
    if (document.getElementById('optMediaCtrlNext').checked) mediaControls.push('next');
    if (document.getElementById('optMediaCtrlRepeat').checked) mediaControls.push('repeat');
    if (mediaControls.join(',') !== 'previous,play_pause,next') newCard.options.media_controls = mediaControls.join(',');
    const volumeControls = [];
    if (document.getElementById('optMediaVolMute').checked) volumeControls.push('mute');
    if (document.getElementById('optMediaVolButtons').checked) volumeControls.push('buttons');
    if (document.getElementById('optMediaVolSet').checked) volumeControls.push('set');
    if (volumeControls.join(',') !== 'mute,buttons') newCard.options.volume_controls = volumeControls.join(',');
    if (mediaVariant === 'full') {
      try {
        const topButtons = JSON.parse(document.getElementById('optMediaTopButtons').value || '[]');
        if (Array.isArray(topButtons) && topButtons.length) newCard.options.top_buttons = topButtons;
      } catch (e) {
        alert(I18N.t('web_card_alert_top_buttons_json'));
        return;
      }
      if (document.getElementById('optMediaArtworkFit').value === 'contain') newCard.options.artwork_fit = 'contain';
      if (document.getElementById('optMediaArtworkRatio').value === 'portrait') newCard.options.artwork_ratio = 'portrait';
    }
  } else if (type === 'camera') {
    const camName = document.getElementById('optName').value.trim();
    if (camName) newCard.options.name = camName;
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'camera.entity';
    if (document.getElementById('optCameraMode').value === 'snapshot') newCard.options.mode = 'snapshot';
    const camInterval = parseInt(document.getElementById('optCameraInterval').value, 10);
    if (Number.isFinite(camInterval) && camInterval !== 2) newCard.options.snapshot_interval = camInterval;
    const camAspect = parseFloat(document.getElementById('optCameraAspect').value);
    if (Number.isFinite(camAspect) && Math.abs(camAspect - 1.7778) > 0.001) newCard.options.aspect = camAspect;
    if (document.getElementById('optCameraFit').value === 'contain') newCard.options.fit = 'contain';
  } else if (type === 'fan') {
    newCard.options.name = document.getElementById('optName').value || 'Fan';
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'fan.entity';
    if (document.getElementById('optFanStyle').value !== 'auto') newCard.options.style = document.getElementById('optFanStyle').value;
    const presetModes = document.getElementById('optFanPresetModes').value.trim();
    if (presetModes) newCard.options.preset_modes = presetModes.split(',').map(s => s.trim()).filter(Boolean);
    const step = parseInt(document.getElementById('optFanStep').value, 10);
    if (!isNaN(step) && step !== 20) newCard.options.step = step;
    if (!document.getElementById('optFanShowCaptions').checked) newCard.options.show_captions = false;
  } else if (type === 'climate') {
    newCard.options.name = document.getElementById('optName').value || 'Climate';
    newCard.options.entity_id = document.getElementById('optEntityId').value || 'climate.entity';
    const hvacModes = document.getElementById('optHvacModes').value.trim();
    if (hvacModes) newCard.options.hvac_modes = hvacModes.split(',').map(s => s.trim()).filter(Boolean);
    if (document.getElementById('optHvacModeStyle').value === 'label') newCard.options.hvac_mode_style = 'label';
    const fanModes = document.getElementById('optFanModes').value.trim();
    if (fanModes) newCard.options.fan_modes = fanModes.split(',').map(s => s.trim()).filter(Boolean);
    if (document.getElementById('optFanModeStyle').value === 'icons') newCard.options.fan_mode_style = 'icons';
    const swingModes = document.getElementById('optSwingModes').value.trim();
    if (swingModes) newCard.options.swing_modes = swingModes.split(',').map(s => s.trim()).filter(Boolean);
    if (document.getElementById('optSwingModeStyle').value === 'icons') newCard.options.swing_mode_style = 'icons';
    if (!document.getElementById('optShowCaptions').checked) newCard.options.show_captions = false;
  } else if (type === 'button_grid') {
    newCard.options.columns = parseInt(document.getElementById('optColumns').value, 10) || 2;
    const iconPosition = document.getElementById('optIconPosition').value;
    if (iconPosition && iconPosition !== 'top') newCard.options.iconPosition = iconPosition;
    newCard.options.buttons = window._pendingGridItems || [];
    collectGridSizeOptions(newCard.options);
    window._pendingGridItems = [];
  } else if (type === 'scene_grid') {
    newCard.options.columns = parseInt(document.getElementById('optColumns').value, 10) || 2;
    newCard.options.scenes = window._pendingGridItems || [];
    const sceneIconPosition = document.getElementById('optIconPosition').value;
    if (sceneIconPosition && sceneIconPosition !== 'top') newCard.options.iconPosition = sceneIconPosition;
    if (!document.getElementById('optShowLabels').checked) newCard.options.show_labels = false;
    if (document.getElementById('optIconFill').checked) newCard.options.icon_fill = true;
    collectGridSizeOptions(newCard.options);
    window._pendingGridItems = [];
  } else if (type === 'apple_tv_remote') {
    if (atvDirectActive()) {
      const appleTv = document.getElementById('atvDirectSelect').value.trim();
      if (!appleTv) { alert(I18N.t('web_card_err_pick_atv')); return; }
      newCard.options.appleTv = appleTv;
    } else if (harmonyAvailable) {
      const hub = document.getElementById('atvHub').value.trim();
      const deviceId = document.getElementById('atvDeviceSelect').value.trim();
      if (!hub || !deviceId) { alert(I18N.t('web_card_alert_pick_hub_device')); return; }
      newCard.options.hub = hub;
      newCard.options.deviceId = deviceId;
    } else {
      newCard.options.deviceId = document.getElementById('optDeviceId').value || '';
    }
    newCard.options.buttons = selectedAtvButtons();
    const atvLayout = document.getElementById('atvLayout').value;
    if (atvLayout !== 'classic') {
      newCard.options.layout = atvLayout;
      if (!document.getElementById('atvShowArtwork').checked) newCard.options.showArtwork = false;
      if (!document.getElementById('atvShowProgress').checked) newCard.options.showProgress = false;
      if (!document.getElementById('atvShowSkip').checked) newCard.options.showSkip = false;
    }
    const atvEntity = document.getElementById('atvEntity').value.trim();
    if (atvEntity) newCard.options.entity = atvEntity;
    const atvApps = collectAtvApps();
    if (atvApps.length) newCard.options.apps = atvApps;
    window._pendingAtvApps = [];
  } else if (type === 'tv_remote' || type === 'speaker_group' || type === 'picture_elements' || type === 'row' || type === 'xbox') {
    const collect = { tv_remote: collectTvRemoteOptions, xbox: collectXboxOptions, speaker_group: collectSpeakerGroupOptions,
      picture_elements: collectPictureElementsOptions, row: collectRowOptions }[type];
    const opts = collect();
    if (!opts) return;
    newCard.options = opts;
  } else if (type === 'clock_weather') {
    newCard.options.entity_id = document.getElementById('optEntityId').value.trim() || 'weather.forecast_home';
    newCard.options.time_format = parseInt(document.getElementById('optTimeFormat').value, 10) || 12;
    newCard.options.forecast_rows = parseInt(document.getElementById('optForecastRows').value, 10);
    if (isNaN(newCard.options.forecast_rows)) newCard.options.forecast_rows = 4;
    const calendarEntity = document.getElementById('optCalendarEntity').value.trim();
    if (calendarEntity) newCard.options.calendar_entity = calendarEntity;
  } else if (type === 'vacuum') {
    const vName = document.getElementById('optName').value.trim();
    if (vName) newCard.options.name = vName;
    newCard.options.entity_id = document.getElementById('optEntityId').value.trim() || 'vacuum.entity';
    const mapImage = document.getElementById('optMapImage').value.trim();
    if (mapImage) newCard.options.map_image = mapImage;
    const mapRotation = parseInt(document.getElementById('optMapRotation').value, 10);
    if (!isNaN(mapRotation) && mapRotation !== 0) newCard.options.map_rotation = mapRotation;
    const mapHeight = parseInt(document.getElementById('optMapHeight').value, 10);
    newCard.options.map_height = isNaN(mapHeight) ? 200 : mapHeight;
    newCard.options.rooms = window._pendingVacuumRooms || [];
    window._pendingVacuumRooms = [];
    const rcaDomain = document.getElementById('optRoomCleanDomain').value.trim();
    const rcaService = document.getElementById('optRoomCleanService').value.trim();
    const rcaParameter = document.getElementById('optRoomCleanParameter').value.trim();
    if (rcaDomain || rcaService || rcaParameter) {
      if (!rcaDomain || !rcaService || !rcaParameter) {
        alert(I18N.t('web_card_alert_room_clean_fields'));
        return;
      }
      newCard.options.room_clean_action = { domain: rcaDomain, service: rcaService, parameter: rcaParameter };
    }
  } else if (type === 'monitor') {
    const rows = (window._pendingMonitorRows || [])
      .map(r => ({ ...r, entity_id: (r.entity_id || '').trim(), name: (r.name || '').trim() }))
      .filter(r => r.entity_id);
    if (!rows.length) { alert(I18N.t('web_card_monitor_err_rows')); return; }
    newCard.options = { ...(window._monitorExtraOptions || {}) };
    const title = document.getElementById('optMonitorTitle').value.trim();
    if (title) newCard.options.title = title;
    if (document.getElementById('optMonitorLayout').value === 'vertical') newCard.options.layout = 'vertical';
    if (document.getElementById('optMonitorMarquee').checked) newCard.options.marquee = true;
    newCard.options.entities = rows.map(r => {
      const out = { ...r };
      if (!out.name) delete out.name;
      return out;
    });
    window._pendingMonitorRows = [];
    window._monitorExtraOptions = {};
  } else if (type === 'plex') {
    const plexHost = document.getElementById('optPlexHost').value.trim();
    const plexToken = document.getElementById('optPlexToken').value.trim();
    const plexMediaEntity = document.getElementById('optPlexMediaEntity').value.trim();
    if (!plexHost || !plexToken || !plexMediaEntity) {
      alert(I18N.t('web_card_alert_plex_fields'));
      return;
    }
    newCard.options.host = plexHost;
    newCard.options.token = plexToken;
    newCard.options.media_entity = plexMediaEntity;
    const plexSource = document.getElementById('optPlexSource').value.trim();
    if (plexSource && plexSource !== 'Plex') newCard.options.source = plexSource;
    const plexPlayEntity = document.getElementById('optPlexPlayEntity').value.trim();
    if (plexPlayEntity) {
      newCard.options.play_entity = plexPlayEntity;
      const plexPlayContentType = document.getElementById('optPlexPlayContentType').value;
      if (plexPlayContentType !== 'video') newCard.options.play_content_type = plexPlayContentType;
    }
    // Written explicitly (even when checked/default) so the app's "any flag
    // present => only what's set shows" logic is unambiguous once the
    // builder has touched this card.
    newCard.options.show_on_deck = document.getElementById('optPlexShowOnDeck').checked;
    newCard.options.show_recently_added_movies = document.getElementById('optPlexShowMovies').checked;
    newCard.options.show_recently_added_shows = document.getElementById('optPlexShowShows').checked;
    const plexItemsPerRow = parseInt(document.getElementById('optPlexItemsPerRow').value, 10);
    if (!isNaN(plexItemsPerRow) && plexItemsPerRow !== 12) newCard.options.items_per_row = plexItemsPerRow;
  } else {
    if (type === 'custom') newCard.type = document.getElementById('optCustomType').value.trim() || 'custom';
    try {
      newCard.options = JSON.parse(document.getElementById('optRawJson').value || '{}');
    } catch (e) { alert(I18N.t('web_card_alert_options_json')); return; }
  }

  // Editing a card inside a row: it goes back into the row, not onto the page.
  if (window._rowCtx && window._rowCtx.active) {
    rowChildSaved(newCard);
    return;
  }

  if (editingCard !== null) {
    dashboardData.pages[pageIndex].cards[editingCard] = newCard;
  } else {
    dashboardData.pages[pageIndex].cards.push(newCard);
  }
  cancelCardEdit();
  renderPreview(); updateJsonOutput();
}


// ✕ sits right next to ✎ on every card, so a slip of the finger used to delete
// a card outright — ask first, naming the card.
function removeCard(idx) {
  const card = dashboardData.pages[currentActivePage].cards[idx];
  if (!card) return;
  const o = card.options || {};
  const label = o.title || o.name || (typeof haFriendlyName === 'function' && haFriendlyName(o.entity_id)) || o.entity_id || card.type;
  if (!confirm(I18N.t('web_card_confirm_delete', label === card.type ? card.type : `${label} (${card.type})`))) return;
  dashboardData.pages[currentActivePage].cards.splice(idx, 1);
  if (editingCard === idx) cancelCardEdit();
  else if (editingCard !== null && editingCard > idx) editingCard--;
  renderPreview(); updateJsonOutput();
}

// ---- Icon / color helpers for scene_grid & button_grid previews -----------
// Icons are configured as absolute on-device paths (e.g.
// "/sdcard/astrion/icons/disco.png"). The browser preview can't read the
// device filesystem directly, but when this page is served by the remote's
// own local web server (http://<remote-ip>:8080/builder/) that server also
// exposes those files at /icons/<filename> (see ConfigServer.kt), so we just
// point an <img> there and gracefully fall back (blank/no icon) if it 404s
// — e.g. briefly, before the initial /dashboard.json load finishes.
function iconUrl(path) {
  if (!path) return null;
  const name = String(path).split(/[\\/]/).pop();
  return name ? `/icons/${encodeURIComponent(name)}` : null;
}

// Mirrors SceneGridCard.kt's parseHexColor: "#RRGGBB" is treated as opaque,
// "#AARRGGBB" carries its own alpha channel.
function parseHexColorCss(hex) {
  if (!hex) return null;
  const clean = hex.replace(/^#/, '');
  if (!/^[0-9a-fA-F]{6}$|^[0-9a-fA-F]{8}$/.test(clean)) return null;
  if (clean.length === 6) return `#${clean}`;
  const a = parseInt(clean.slice(0, 2), 16) / 255;
  const r = parseInt(clean.slice(2, 4), 16);
  const g = parseInt(clean.slice(4, 6), 16);
  const b = parseInt(clean.slice(6, 8), 16);
  return `rgba(${r}, ${g}, ${b}, ${a.toFixed(3)})`;
}

// Mirrors SceneGridCard.kt's luminance() + textColor threshold (0.75).
function textColorForBg(cssColor) {
  const m = cssColor.match(/rgba?\(([^)]+)\)/);
  let r, g, b;
  if (m) {
    [r, g, b] = m[1].split(',').map(s => parseFloat(s));
  } else {
    const clean = cssColor.replace('#', '');
    r = parseInt(clean.slice(0, 2), 16);
    g = parseInt(clean.slice(2, 4), 16);
    b = parseInt(clean.slice(4, 6), 16);
  }
  const luminance = 0.2126 * (r / 255) + 0.7152 * (g / 255) + 0.0722 * (b / 255);
  return luminance > 0.75 ? '#141414' : '#F0F2F6';
}

// Icon not found (wrong filename, or previewing outside the remote's own
// server where /icons/ isn't served) — fall back to the same blank-spacer
// look the real app uses when BitmapFactory.decodeFile() returns null.
function onSceneIconError(img) {
  const spacer = document.createElement('div');
  spacer.className = 'st-icon-spacer';
  img.replaceWith(spacer);
}

function onButtonIconError(img) {
  const tile = img.closest('.preview-button-tile');
  img.remove();
  if (tile) tile.classList.replace('has-icon', 'no-icon');
}

// ---- Icon field: text input + live thumbnail + picker button --------------
// Every card option that takes an icon path (optIcon on name/entity cards and
// cover, giIcon on scene_grid/button_grid items) renders through this one
// helper, so the picker/thumbnail behaviour stays identical everywhere
// instead of hand-typing a path being the only option.

function iconFieldHtml(id) {
  return `
    <label>${I18N.t('web_card_icon_label')}</label>
    <div class="icon-field-row">
      <input type="text" id="${id}" placeholder="/sdcard/astrion/icons/xxx.png" oninput="updateIconThumb('${id}')">
      <img class="icon-field-thumb" id="${id}Thumb" alt="">
      <button type="button" class="secondary" onclick="openIconPicker('${id}')">${I18N.t('web_icon_choose')}</button>
    </div>
  `;
}

// Keeps the little thumbnail next to an icon field in sync with its current
// value — called on typing (oninput, see iconFieldHtml) and whenever a field
// is populated programmatically (editing an existing card/item). Same
// silent-fallback rule as everywhere else: no thumbnail if the path is empty,
// unresolvable, or this copy of the builder has no device behind /icons/.
function updateIconThumb(id) {
  const input = document.getElementById(id);
  const thumb = document.getElementById(id + 'Thumb');
  if (!input || !thumb) return;
  const url = iconUrl(input.value.trim());
  if (!url) {
    thumb.classList.remove('shown');
    return;
  }
  thumb.onerror = () => thumb.classList.remove('shown');
  thumb.src = url;
  thumb.classList.add('shown');
}

// Which icon field the picker modal is currently filling in — set by
// openIconPicker, read by selectIcon.
let iconPickerTarget = null;

// Opens the icon-picker modal for the given field id, listing every icon
// already uploaded to the device (GET /icons-list, see ConfigServer.kt) as
// clickable thumbnails.
async function openIconPicker(targetId) {
  iconPickerTarget = targetId;
  const grid = document.getElementById('iconPickerGrid');
  grid.innerHTML = `<div class="icon-picker-empty">${I18N.t('media_loading')}</div>`;
  document.getElementById('iconPickerModal').classList.add('open');

  let names;
  try {
    const res = await fetch('/icons-list');
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    names = await res.json();
  } catch (e) {
    grid.innerHTML = `
      <div class="icon-picker-error">
        ${I18N.t('web_icon_error')}
      </div>`;
    return;
  }

  if (!names.length) {
    grid.innerHTML = `<div class="icon-picker-empty">${I18N.t('web_icon_empty_hint')}</div>`;
    return;
  }

  grid.innerHTML = names.map(name => `
    <div class="icon-picker-item" onclick="selectIcon('${name.replace(/'/g, "\\'")}')">
      <img src="${iconUrl(name)}" alt="" loading="lazy">
      <span>${name}</span>
    </div>
  `).join('');
}

function selectIcon(name) {
  if (!iconPickerTarget) return;
  document.getElementById(iconPickerTarget).value = `/sdcard/astrion/icons/${name}`;
  updateIconThumb(iconPickerTarget);
  closeIconPicker();
}

function closeIconPicker() {
  document.getElementById('iconPickerModal').classList.remove('open');
  iconPickerTarget = null;
}

// ---- monitor card rows ------------------------------------------------------
// One editable line per sensor: entity (autocompleted from Home Assistant when
// the builder runs on the remote) + optional display name, reorderable.

function renderMonitorRows() {
  const list = document.getElementById('monitorRowsList');
  if (!list) return;
  const rows = window._pendingMonitorRows || [];
  if (!rows.length) {
    list.innerHTML = `<div class="hint">${I18N.t('web_card_monitor_no_rows')}</div>`;
    return;
  }
  const esc = v => String(v == null ? '' : v).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  list.innerHTML = rows.map((r, i) => `
    <div class="list-item" style="gap:6px;align-items:center">
      <input type="text" class="mon-entity" data-i="${i}" value="${esc(r.entity_id)}" placeholder="sensor.avr_video" style="flex:3;min-width:0;margin:0" oninput="updateMonitorRow(${i}, 'entity_id', this.value)">
      <input type="text" data-i="${i}" value="${esc(r.name)}" placeholder="${I18N.t('web_card_monitor_name_ph')}" style="flex:2;min-width:0;margin:0" oninput="updateMonitorRow(${i}, 'name', this.value)">
      <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_up')}" onclick="moveMonitorRow(${i}, -1)">↑</span>
      <span class="remove" style="color:#00E5FF" title="${I18N.t('web_preview_move_down')}" onclick="moveMonitorRow(${i}, 1)">↓</span>
      <span class="remove" onclick="removeMonitorRow(${i})">✕</span>
    </div>`).join('');
  list.querySelectorAll('.mon-entity').forEach(el => {
    if (typeof attachEntityAutocomplete === 'function') attachEntityAutocomplete(el, null);
    // Autocomplete picks set the value without an input event — keep the row in sync on change too.
    el.addEventListener('change', () => updateMonitorRow(Number(el.dataset.i), 'entity_id', el.value));
  });
}

function updateMonitorRow(i, field, value) {
  const row = (window._pendingMonitorRows || [])[i];
  if (row) row[field] = value;
}

function addMonitorRow() {
  window._pendingMonitorRows = window._pendingMonitorRows || [];
  window._pendingMonitorRows.push({ entity_id: '', name: '' });
  renderMonitorRows();
  const inputs = document.querySelectorAll('#monitorRowsList .mon-entity');
  if (inputs.length) inputs[inputs.length - 1].focus();
}

function removeMonitorRow(i) {
  window._pendingMonitorRows.splice(i, 1);
  renderMonitorRows();
}

function moveMonitorRow(i, delta) {
  const rows = window._pendingMonitorRows || [];
  const j = i + delta;
  if (j < 0 || j >= rows.length) return;
  [rows[i], rows[j]] = [rows[j], rows[i]];
  renderMonitorRows();
}

// switch card: the PNG icon field only shows for "PNG icon".
function onSwitchIconKindChange() {
  const kind = document.getElementById('optSwitchIconKind');
  const box = document.getElementById('switchPngIcon');
  if (kind && box) box.style.display = kind.value === 'png' ? '' : 'none';
}

// select card: the "dropdown" layout has no icon, so no icon colour either.
function onSelectLayoutChange() {
  const layout = document.getElementById('optSelectLayout');
  const row = document.getElementById('selectIconColorRow');
  if (layout && row) row.style.display = layout.value === 'dropdown' ? 'none' : '';
}