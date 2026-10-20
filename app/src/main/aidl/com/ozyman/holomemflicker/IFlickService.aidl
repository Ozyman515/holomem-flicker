package com.ozyman.holomemflicker;

interface IFlickService {
    void destroy() = 16777114;

    String start(String configJson) = 1;
    void stop() = 2;
    String status() = 3;
    String monitor() = 4;
    String lastKey() = 5;
    String listDevices() = 6;
    String testTouch(float x, float y) = 7;
    String installUpdate(String url) = 8;
    String inputState() = 9;
    void setMode(int mode) = 10;
    void setOverlay(boolean on) = 11;
    String grantOverlay(String pkg) = 12;
}
