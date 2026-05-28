package com.example.myapplication;

import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.Polyline;

import java.util.ArrayList;
import java.util.List;
import android.util.Log;

public class WhuRoute {

    public static Polyline parseRouteGeoJson(String jsonString) {
        try {
            JSONObject root = new JSONObject(jsonString);
            JSONArray features = root.optJSONArray("features");
            if (features == null || features.length() == 0) {
                return null;
            }

            Polyline polyline = new Polyline();
            polyline.getOutlinePaint().setColor(Color.GREEN);
            polyline.getOutlinePaint().setStrokeWidth(10f);

            List<GeoPoint> points = new ArrayList<>();

            for (int i = 0; i < features.length(); i++) {
                JSONObject feature = features.getJSONObject(i);
                JSONObject geometry = feature.optJSONObject("geometry");
                if (geometry == null) continue;

                String type = geometry.optString("type");
                JSONArray coordinates = geometry.optJSONArray("coordinates");
                if (coordinates == null) continue;

                if ("LineString".equals(type) || "MultiLineString".equals(type)) {
                    // WFS default is EPSG:4326 for some configurations, but let's check what EPSG we used.
                    // If pgrouting returns LineString, let's extract it.
                    // For pgrouting route, coordinates are usually 2D.
                    extractPoints(coordinates, type, points);
                }
            }

            if (!points.isEmpty()) {
                polyline.setPoints(points);
                return polyline;
            }
        } catch (Exception e) {
            Log.e("WhuRoute", "Failed to parse route GeoJSON", e);
        }
        return null;
    }

    private static void extractPoints(JSONArray coordinates, String type, List<GeoPoint> points) throws Exception {
        if ("LineString".equals(type)) {
            for (int j = 0; j < coordinates.length(); j++) {
                JSONArray coord = coordinates.getJSONArray(j);
                double x = coord.getDouble(0);
                double y = coord.getDouble(1);

                // Assuming the coordinates in GeoServer are Web Mercator (EPSG:3857) or WGS84
                // Let's assume the WFS request returns EPSG:3857 if we requested it, or if it's stored that way.
                // Actually wait, if coordinates are large (e.g. 1.27E7), they are EPSG:3857.
                // We will handle unprojection here if they exceed normal lat/lon bounds
                if (Math.abs(x) > 180 || Math.abs(y) > 90) {
                    double[] lonlat = WebMecator.unproject(x, y);
                    points.add(new GeoPoint(lonlat[1], lonlat[0])); // lat, lon
                } else {
                    points.add(new GeoPoint(y, x)); // lat, lon
                }
            }
        } else if ("MultiLineString".equals(type)) {
            for (int j = 0; j < coordinates.length(); j++) {
                JSONArray linePart = coordinates.getJSONArray(j);
                extractPoints(linePart, "LineString", points);
            }
        }
    }
}

