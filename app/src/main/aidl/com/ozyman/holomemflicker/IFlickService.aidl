package com.ozyman.holomemflicker;

interface IFlickService {
    void destroy() = 16777114;

    String start(String configJson) = 1;
    void stop() = 2;
    String status() = 3;
    String monitor() = 4;
    String lastKey() = 5;
    String listDevices() = 6;
}
