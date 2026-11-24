// Клиентская копия интерфейса vendor-HAL внешнего экрана thor2 (ALT AT-M140).
// Восстановлена по стоковому framework.jar: порядок методов = коды транзакций (1..9), менять нельзя.
// Сервер: /vendor/bin/hw/vendor.hnlens.hardware.presentation-service, экземпляр "default", V1.
package vendor.hnlens.hardware.presentation;

import vendor.hnlens.hardware.presentation.ILsDisplayRegion;
import vendor.hnlens.hardware.presentation.ILsLCDPanelInfo;

interface ILsPresentation {
    void open();
    void close();
    int getBrightness();
    void setBrightness(int level);
    int checkESD();
    ILsLCDPanelInfo getPanleInfo();
    void displayRegion(in ILsDisplayRegion region);
    void fullDisplayColor(int color);
    int getDisplayStatus();
}
