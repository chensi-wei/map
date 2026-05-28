package com.example.myapplication;

public class WebMecator {

    // Project lon/lat to Web Mercator
    public static double[] project(double lon, double lat) {
        double x = lon * 20037508.34 / 180;
        double y = Math.log(Math.tan((90 + lat) * Math.PI / 360)) / (Math.PI / 180);
        y = y * 20037508.34 / 180;
        return new double[]{x, y};
    }

    // Unproject Web Mercator to lon/lat
    public static double[] unproject(double x, double y) {
        double lon = x / 20037508.34 * 180;
        double lat = y / 20037508.34 * 180;
        lat = 180 / Math.PI * (2 * Math.atan(Math.exp(lat * Math.PI / 180)) - Math.PI / 2);
        return new double[]{lon, lat};
    }
}
