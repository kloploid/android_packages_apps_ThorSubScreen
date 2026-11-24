// Сведения о панели (имена полей — как в стоке, включая опечатки).
package vendor.hnlens.hardware.presentation;

parcelable ILsLCDPanelInfo {
    String lcd_name;
    int pixel_width;
    int pixel_height;
    int physical_width;
    int physical_height;
    int lcd_bpp;
    int lcd_orientation;
    int lcd_bl_level_nums;
    int max_bl_brightnees;
    int current_bl_brightnees;
}
