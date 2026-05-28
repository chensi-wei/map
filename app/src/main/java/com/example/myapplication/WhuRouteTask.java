package com.example.myapplication;

import android.os.Handler;
import android.os.Looper;

import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import android.util.Log;

public class WhuRouteTask {

    // TODO: 根据实际组号调整, 这里用的whu12
    private static final String WFS_URL = "http://59.175.92.18:40083/geoserver/whu12/ows";
    private static final String LAYER_NAME = "whu12:route";

    public static void requestRoute(double startLat, double startLon, double endLat, double endLon, WhuRouteListener listener) {
        new Thread(() -> {
            try {
                // 将经纬度转为Web Mercator坐标，因为WFS要求传入EPSG:3857的坐标(或者是按参数传入的)
                double[] startPoint = WebMecator.project(startLon, startLat);
                double[] endPoint = WebMecator.project(endLon, endLat);

                // 拼装查询参数，这里是经典的pgarouting viewparams方案
                // viewparams=x1:%,y1:%,x2:%,y2:%
                // 注意：由于很多同学在GeoServer发布SQL View时，验证正则表达式默认是 ^[\w\d\s]+$ （不包含小数点）
                // 传带小数点的投影坐标会导致报错 Invalid value for parameter y1，所以这里我们取整！
                String viewParams = String.format(Locale.US, "x1:%d;y1:%d;x2:%d;y2:%d",
                        Math.round(startPoint[0]),
                        Math.round(startPoint[1]),
                        Math.round(endPoint[0]),
                        Math.round(endPoint[1]));

                String requestUrl = WFS_URL + "?service=WFS&version=1.0.0&request=GetFeature" +
                        "&typeName=" + LAYER_NAME +
                        "&outputFormat=application/json" +
                        "&viewparams=" + viewParams +
                        "&srsName=EPSG:3857";

                URL url = new URL(requestUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) {
                        response.append(line);
                    }
                    in.close();

                    Polyline routePolyline = WhuRoute.parseRouteGeoJson(response.toString());
                    postResult(listener, routePolyline, null);
                } else {
                    postResult(listener, null, "Server returned HTTP " + responseCode);
                }
            } catch (Exception e) {
                Log.e("WhuRouteTask", "Failed to request route", e);
                postResult(listener, null, e.getMessage());
            }
        }).start();
    }

    private static void postResult(WhuRouteListener listener, Polyline route, String errorMsg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (listener != null) {
                if (route != null) {
                    listener.onRouteSuccess(route);
                } else {
                    listener.onRouteFail(errorMsg != null ? errorMsg : "Unknown error");
                }
            }
        });
    }
}

