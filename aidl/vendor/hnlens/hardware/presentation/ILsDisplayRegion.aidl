// Кадр (или его часть) для внешнего экрана: RGB565, координаты включительно.
package vendor.hnlens.hardware.presentation;

parcelable ILsDisplayRegion {
    byte[] displayBuffer;
    long bufferSize;
    int xStart;
    int xEnd;
    int yStart;
    int yEnd;
}
