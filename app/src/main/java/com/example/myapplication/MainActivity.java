package com.example.myapplication;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.osmdroid.api.IMapController;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.EditText;
import android.view.HapticFeedbackConstants;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import android.location.OnNmeaMessageListener;

import android.graphics.Color;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private MapView mapView;
    private Marker locationMarker;
    private Marker destinationMarker;
    private LocationManager locationManager;
    private OnNmeaMessageListener nmeaListener;

    private static final String WMS_URL = "http://59.175.92.18:40083/geoserver/whu12/wms";
    private static final String WFS_POI_URL = "http://59.175.92.18:40083/geoserver/whu12/ows";
    private static final String WMS_LAYER = "whu12:whu12";

    private static double tile2MercatorX(int x, int z) {
        return (x / Math.pow(2.0, z)) * 40075016.686 - 20037508.343;
    }
    private static double tile2MercatorY(int y, int z) {
        return ((Math.pow(2.0, z) - y) / Math.pow(2.0, z)) * 40075016.686 - 20037508.343;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Context ctx = getApplicationContext();
        Configuration.getInstance().load(ctx, androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx));
        Configuration.getInstance().setUserAgentValue(getPackageName());

        setContentView(R.layout.activity_main);

        mapView = findViewById(R.id.map_view);
        mapView.setMultiTouchControls(true);

        OnlineTileSourceBase customWmsSource = new OnlineTileSourceBase("MyWMS", 1, 20, 256, ".png", new String[]{WMS_URL}) {
            @Override
            public String getTileURLString(long pMapTileIndex) {
                int z = MapTileIndex.getZoom(pMapTileIndex);
                int x = MapTileIndex.getX(pMapTileIndex);
                int y = MapTileIndex.getY(pMapTileIndex);

                double minx = tile2MercatorX(x, z);
                double maxy = tile2MercatorY(y, z);
                double maxx = tile2MercatorX(x + 1, z);
                double miny = tile2MercatorY(y + 1, z);

                return getBaseUrl() + "?service=WMS&version=1.1.1&request=GetMap" +
                        "&layers=" + WMS_LAYER +
                        "&styles=" +
                        "&bbox=" + minx + "," + miny + "," + maxx + "," + maxy +
                        "&width=256&height=256" +
                        "&srs=EPSG:3857" +
                        "&format=image/png" +
                        "&transparent=true";
            }
        };
        mapView.setTileSource(customWmsSource);

        mapView.setBackgroundColor(Color.parseColor("#FFFFF9F0"));
        
        if (mapView.getOverlayManager().getTilesOverlay() != null) {
            mapView.getOverlayManager().getTilesOverlay().setColorFilter(null);
            mapView.getOverlayManager().getTilesOverlay().setLoadingBackgroundColor(Color.parseColor("#FFFFF9F0"));
            mapView.getOverlayManager().getTilesOverlay().setLoadingLineColor(Color.parseColor("#4DFFFFFF"));
        }

        IMapController mapController = mapView.getController();
        mapController.setZoom(16.0);
        mapController.setCenter(new GeoPoint(30.538, 114.3618));

        locationMarker = new Marker(mapView);
        locationMarker.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_location));
        locationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
        mapView.getOverlays().add(locationMarker);

        destinationMarker = new Marker(mapView);
        destinationMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
        destinationMarker.setTitle("目的地");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.INTERNET
                }, 100);
            } else {
                startLocationUpdates();
            }
        } else {
            startLocationUpdates();
        }

        EditText etSearch = findViewById(R.id.et_search);
        Button btnSearch = findViewById(R.id.btn_search);
        btnSearch.setOnClickListener(v -> {
            // 点击震感
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            
            String q = etSearch.getText().toString().trim();
            if (q.isEmpty()) {
                Toast.makeText(this, "请输入关键字", Toast.LENGTH_SHORT).show();
            } else {
                searchPoiByName(q);
            }
        });

        FloatingActionButton btnLocate = findViewById(R.id.btn_locate);
        btnLocate.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (currentLocation != null) {
                mapView.getController().animateTo(currentLocation);
                Toast.makeText(MainActivity.this, "已返回当前位置", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(MainActivity.this, "当前定位还没获取到，请稍候", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private GeoPoint currentLocation = null;
    private GeoPoint destinationLocation = null;
    private Polyline currentRouteLine = null;

    private void startLocationUpdates() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null) {
            try {
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000, 1, this);
                }
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2000, 1, this);
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    nmeaListener = new OnNmeaMessageListener() {
                        @Override
                        public void onNmeaMessage(String message, long timestamp) {
                            parseNmeaMessage(message);
                        }
                    };
                    locationManager.addNmeaListener(nmeaListener, null);
                }
            } catch (SecurityException e) {
                Log.e("MainActivity", "Failed to request location updates", e);
            }
        }
    }

    private void parseNmeaMessage(String nmea) {
        try {
            if (nmea.startsWith("$GPGGA") || nmea.startsWith("$GNGGA")) {
                String[] parts = nmea.split(",");
                if (parts.length > 5 && !parts[2].isEmpty() && !parts[4].isEmpty()) {
                    double lat = parseNmeaCoord(parts[2], parts[3]);
                    double lon = parseNmeaCoord(parts[4], parts[5]);

                    // 任务6：使用自己编写的坐标系转换程序，将经纬度(WGS84)投影到Web墨卡托，再反投影回去（演示验证转换程序的可用性）
                    double[] mercatorXY = WebMecator.project(lon, lat);
                    double[] coordWgs84 = WebMecator.unproject(mercatorXY[0], mercatorXY[1]);

                    updateLocationOnMap(coordWgs84[1], coordWgs84[0]);
                }
            } else if (nmea.startsWith("$GPRMC") || nmea.startsWith("$GNRMC")) {
                String[] parts = nmea.split(",");
                if (parts.length > 5 && !parts[3].isEmpty() && !parts[5].isEmpty()) {
                    double lat = parseNmeaCoord(parts[3], parts[4]);
                    double lon = parseNmeaCoord(parts[5], parts[6]);

                    double[] mercatorXY = WebMecator.project(lon, lat);
                    double[] coordWgs84 = WebMecator.unproject(mercatorXY[0], mercatorXY[1]);

                    updateLocationOnMap(coordWgs84[1], coordWgs84[0]);
                }
            }
        } catch (Exception e) {
            Log.e("NMEA", "Parse error: " + nmea, e);
        }
    }

    private double parseNmeaCoord(String coord, String dir) {
        if (coord.isEmpty()) return 0.0;
        int dotIndex = coord.indexOf('.');
        if (dotIndex < 2) return 0.0;
        int degEnd = dotIndex - 2;
        int degrees = Integer.parseInt(coord.substring(0, degEnd));
        double minutes = Double.parseDouble(coord.substring(degEnd));
        double decimalDeg = degrees + (minutes / 60.0);
        if (dir.equals("S") || dir.equals("W")) {
            decimalDeg = -decimalDeg;
        }
        return decimalDeg;
    }

    private void updateLocationOnMap(double lat, double lon) {
        new Handler(Looper.getMainLooper()).post(() -> {
            currentLocation = new GeoPoint(lat, lon);
            locationMarker.setPosition(currentLocation);
            mapView.invalidate();
            
            // 实时检查偏航状态，若偏离过大则重算
            checkOffRouteAndReplan();
        });
    }

    private void checkOffRouteAndReplan() {
        if (currentRouteLine == null || destinationLocation == null || currentLocation == null) {
            return;
        }
        
        // 距离终点小于50米时，视为已到达，不进行重算
        if (currentLocation.distanceToAsDouble(destinationLocation) < 50) {
            return;
        }

        List<GeoPoint> pts = currentRouteLine.getActualPoints(); 
        // osmdroid Polyline 中通常用 getActualPoints() 或 getPoints() 获取经纬度节点队列
        if (pts == null || pts.size() < 2) return;

        double minDistSq = Double.MAX_VALUE;
        double[] p = WebMecator.project(currentLocation.getLongitude(), currentLocation.getLatitude());
        
        for (int i = 0; i < pts.size() - 1; i++) {
            GeoPoint p1 = pts.get(i);
            GeoPoint p2 = pts.get(i + 1);
            double[] a = WebMecator.project(p1.getLongitude(), p1.getLatitude());
            double[] b = WebMecator.project(p2.getLongitude(), p2.getLatitude());
            
            double d = pointToSegmentDistSq(p[0], p[1], a[0], a[1], b[0], b[1]);
            if (d < minDistSq) {
                minDistSq = d;
            }
        }
        
        // 如果用户距离推荐线段的最短距离平方大于 6400 (即约80米)，视为偏航
        if (minDistSq > 6400) {
            Log.d("MainActivity", "User off route! DistanceSq: " + minDistSq);
            Toast.makeText(this, "检测到偏航，正在重新规划路线...", Toast.LENGTH_SHORT).show();
            // 先将原有路线置空，防止在重算返回之前发生无限循环调用
            mapView.getOverlays().remove(currentRouteLine);
            currentRouteLine = null;
            
            requestRouteToTarget();
        }
    }

    // 利用投影平面内的点到线段距离算法来近似判断偏航
    private double pointToSegmentDistSq(double px, double py, double ax, double ay, double bx, double by) {
        double l2 = (bx - ax) * (bx - ax) + (by - ay) * (by - ay);
        if (l2 == 0) return (px - ax) * (px - ax) + (py - ay) * (py - ay);
        double t = ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l2;
        t = Math.max(0, Math.min(1, t));
        double projX = ax + t * (bx - ax);
        double projY = ay + t * (by - ay);
        return (px - projX) * (px - projX) + (py - projY) * (py - projY);
    }

    private void searchPoiByName(String keyword) {
        if (currentLocation == null) {
            Toast.makeText(this, "定位中，请稍后", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "正在为您搜索: " + keyword, Toast.LENGTH_SHORT).show();

        new Thread(() -> {
            try {
                String cql = "NAME LIKE '%" + keyword + "%'";
                String urlCql = URLEncoder.encode(cql, StandardCharsets.UTF_8.name());

                String requestUrl = WFS_POI_URL + "?service=WFS&version=1.0.0&request=GetFeature" +
                        "&typeName=whu12:poi_point_wm&maxFeatures=20&outputFormat=application/json&cql_filter=" + urlCql;

                URL url = new URL(requestUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(8000);
                if (conn.getResponseCode() == 200) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder res = new StringBuilder();
                    String line;
                    while ((line = in.readLine()) != null) res.append(line);
                    in.close();

                    JSONObject root = new JSONObject(res.toString());
                    JSONArray features = root.optJSONArray("features");
                    if (features == null || features.length() == 0) {
                        new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(MainActivity.this, "抱歉，没搜到相关的地点", Toast.LENGTH_SHORT).show());
                        return;
                    }

                    String[] poiNames = new String[features.length()];
                    double[][] poiCoords = new double[features.length()][2];

                    for (int i = 0; i < features.length(); i++) {
                        JSONObject props = features.getJSONObject(i).getJSONObject("properties");
                        JSONArray coords = features.getJSONObject(i).getJSONObject("geometry").getJSONArray("coordinates");
                        poiNames[i] = props.optString("NAME", "未知地点");
                        double[] lonlat = WebMecator.unproject(coords.getDouble(0), coords.getDouble(1));
                        poiCoords[i][0] = lonlat[1];
                        poiCoords[i][1] = lonlat[0];
                    }

                    new Handler(Looper.getMainLooper()).post(() -> showPoiSelector(poiNames, poiCoords));
                }
            } catch (Exception e) {
                Log.e("MainActivity", "Name search POI error", e);
            }
        }).start();
    }
    
    private void showPoiSelector(String[] names, double[][] coords) {
        if (names.length == 0) {
            Toast.makeText(this, "没找到附近的命名点", Toast.LENGTH_SHORT).show();
            return;
        }
        
        new AlertDialog.Builder(this)
            .setTitle("请选择目的地")
            .setItems(names, (dialog, which) -> {
                // 选择时也添加震感
                mapView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                double targetLat = coords[which][0];
                double targetLon = coords[which][1];
                setDestAndRoute(names[which], targetLat, targetLon);
            })
            .show();
    }

    private void setDestAndRoute(String name, double lat, double lon) {
        destinationLocation = new GeoPoint(lat, lon);
        destinationMarker.setPosition(destinationLocation);
        destinationMarker.setTitle(name);
        if (!mapView.getOverlays().contains(destinationMarker)) {
            mapView.getOverlays().add(destinationMarker);
        }

        Toast.makeText(this, "正在规划路径到: " + name, Toast.LENGTH_SHORT).show();
        requestRouteToTarget();
    }

    private void requestRouteToTarget() {
        WhuRouteTask.requestRoute(currentLocation.getLatitude(), currentLocation.getLongitude(), destinationLocation.getLatitude(), destinationLocation.getLongitude(), new WhuRouteListener() {
            @Override
            public void onRouteSuccess(Polyline routeLine) {
                if (currentRouteLine != null) {
                    mapView.getOverlays().remove(currentRouteLine);
                }
                currentRouteLine = routeLine;
                mapView.getOverlays().add(currentRouteLine);
                mapView.invalidate();
                Toast.makeText(MainActivity.this, "路径规划成功", Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onRouteFail(String reason) {
                Toast.makeText(MainActivity.this, "路径规划失败: " + reason, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 100) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                Toast.makeText(this, "未授予位置权限，无法定位", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        // 允许高层位置回调更新，解决在室内没有卫星信号从而无NMEA时，完全无法定位（导致无法规划和搜索）的问题
        updateLocationOnMap(location.getLatitude(), location.getLongitude());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mapView != null) mapView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (locationManager != null) {
            locationManager.removeUpdates(this);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && nmeaListener != null) {
                locationManager.removeNmeaListener(nmeaListener);
            }
        }
    }
}