package bridging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fungsi.koneksiDB;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.X509TrustManager;
import javax.swing.JOptionPane;
import org.springframework.http.*;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

public class ApiELVAPACS {

    private final Connection koneksi = koneksiDB.condb();
    private final ObjectMapper mapper = new ObjectMapper();

    private String XKey, XId, URL;

    /* ===============================
       REST TEMPLATE (SINGLETON)
       =============================== */
    private static RestTemplate restTemplate;

    private RestTemplate getRest() {
        if (restTemplate == null) {
            synchronized (ApiELVAPACS.class) {
                if (restTemplate == null) {
                    restTemplate = buildUnsafeRestTemplate();
                }
            }
        }
        return restTemplate;
    }

private RestTemplate buildUnsafeRestTemplate() {
    try {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new X509TrustManager[]{
            new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }
        }, new SecureRandom());

        // ===== Apache HttpClient LAWAS =====
        org.apache.http.params.HttpParams params =
                new org.apache.http.params.BasicHttpParams();

        org.apache.http.params.HttpConnectionParams.setConnectionTimeout(params, 10000);
        org.apache.http.params.HttpConnectionParams.setSoTimeout(params, 20000);

        org.apache.http.conn.scheme.SchemeRegistry registry =
                new org.apache.http.conn.scheme.SchemeRegistry();

        registry.register(
            new org.apache.http.conn.scheme.Scheme(
                "https", 443,
                new org.apache.http.conn.ssl.SSLSocketFactory(
                    sslContext,
                    org.apache.http.conn.ssl.SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER
                )
            )
        );

        org.apache.http.impl.client.DefaultHttpClient httpClient =
                new org.apache.http.impl.client.DefaultHttpClient(params);

        httpClient.getConnectionManager().getSchemeRegistry().register(
                registry.getScheme("https"));

        HttpComponentsClientHttpRequestFactory factory =
                new HttpComponentsClientHttpRequestFactory(httpClient);

        return new RestTemplate(factory);

    } catch (Exception e) {
        throw new RuntimeException("Gagal init RestTemplate ELVA", e);
    }
}



    /* ===============================
       CONSTRUCTOR
       =============================== */
    public ApiELVAPACS() {
        try {
            XKey = koneksiDB.XKEYAPIELVA();
            XId = koneksiDB.XIDAPIELVA();
            URL  = koneksiDB.URLELVAPACS();
        } catch (Exception e) {
            System.out.println("Init ELVA error : " + e);
        }
    }

    /* ===============================
       HEADER BUILDER
       =============================== */
    private HttpHeaders buildHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.add("x-id", XId);
        h.add("x-key", XKey);
        return h;
    }

    /* ===============================
       HELPER: RESPONSE SUCCESS
       =============================== */
private boolean isElvaSuccess(int httpCode, String body) {

    if (httpCode != 200 && httpCode != 201) {
        return false;
    }

    if (body == null || body.trim().isEmpty()) {
        return true;
    }

    String resp = body.toLowerCase();

    if (resp.contains("berhasil")) return true;

    if (!resp.contains("gagal") && !resp.contains("error")) return true;

    return false;
}

    /* ===============================
       HELPER: RO / CA
       =============================== */
    private ResponseEntity<String>  kirimROCA(String control, String accNo) throws Exception {
        JsonNode body = mapper.createObjectNode()
            .put("PatientID", "-")
            .put("PatientName", "-")
            .put("OrderControl", control)
            .put("AccessionNumber", accNo)
            .put("Modality", "-");

        HttpEntity<String> entity =
            new HttpEntity<>(mapper.writeValueAsString(body), buildHeaders());

        return getRest()
            .exchange(URL + "/order/", HttpMethod.POST, entity, String.class);
           }

    /* ===============================
       CANCEL ORDER (RO -> CA)
       =============================== */
    public void CancelOrder(String nopermintaan) {
        String sql =
            "SELECT concat(replace(permintaan_radiologi.noorder,'PR20',''),replace(permintaan_pemeriksaan_radiologi.kd_jenis_prw,'RD','')) AS noorder " +
            "FROM permintaan_radiologi pr " +
            "INNER JOIN permintaan_pemeriksaan_radiologi ppr ON ppr.noorder=pr.noorder " +
            "WHERE pr.noorder=?";

        try (PreparedStatement ps = koneksi.prepareStatement(sql)) {
            ps.setString(1, nopermintaan);
            try (ResultSet rs = ps.executeQuery()) {

                List<String> sukses = new ArrayList<>();
                List<String> gagal  = new ArrayList<>();

                while (rs.next()) {
                    String acc = rs.getString("noorder");

                    ResponseEntity<String> roResp = kirimROCA("RO", acc);

                    int roCode = roResp.getStatusCode().value();
                    String roBody = roResp.getBody();

                    if (!isElvaSuccess(roCode, roBody)) {
                        gagal.add(acc + " (RO gagal)");
                        continue;
                    }

                    ResponseEntity<String> caResp = kirimROCA("CA", acc);

                    int caCode = caResp.getStatusCode().value();
                    String caBody = caResp.getBody();

                    if (isElvaSuccess(caCode, caBody)) {
                        sukses.add(acc);
                    } else {
                        gagal.add(acc + " (CA gagal)");
                    }
                }

                tampilkanHasil("Cancel Order ELVA", sukses, gagal);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                "Cancel order ELVA gagal\n" + e.getMessage());
        }
    }

    /* ===============================
       KIRIM RALAN / RANAP (UNIFIED)
       =============================== */
    private void kirimOrder(String sql, String nopermintaan, boolean ranap) {
        try (PreparedStatement ps = koneksi.prepareStatement(sql)) {
            ps.setString(1, nopermintaan);
            try (ResultSet rs = ps.executeQuery()) {

                List<String> sukses = new ArrayList<>();
                List<String> gagal  = new ArrayList<>();

                while (rs.next()) {
                    JsonNode body = mapper.createObjectNode()
                        .put("PatientID", rs.getString("no_rkm_medis"))
                        .put("PatientName", rs.getString("nm_pasien"))
                        .put("PatientSex", rs.getString("jk"))
                        .put("PatientBirthday", rs.getString("tgl_lahir"))
                        .put("PatientWeight", "0")
                        .put("PatientClass", "I")
                        .put("Ward", rs.getString("nm_poli"))
                        .put("AttendingDoctor", "-")
                        .put("ReferringDoctor", rs.getString("nm_dokter"))
                        .put("OrderControl", "NW")
                        .put("OrderDepartment", "-")
                        .put("AccessionNumber", rs.getString("noorder"))
                        .put("StudyCode", rs.getString("kd_jenis_prw"))
                        .put("StudyName", rs.getString("nm_perawatan"))
                        .put("OrderDatetime", rs.getString("tgl_permintaan"))
                        .put("ScheduledDatetime", rs.getString("tgl_sampel"))
                        .put("ClinicComments", rs.getString("diagnosa_klinis"))
                        .put("SicknessName", "-")
                        .put("ReasonForStudy", "-")
                        .put("BodyPart", "-")
                        .put("OrderingDoctor", "-")
                        .put("ExamRoom", "-")
                        .put("Modality", rs.getString("modality"))
                        .put("OperatorName", "-")
                        .put("ExamUrgent", "0");
String jsonRequest = mapper
        .writerWithDefaultPrettyPrinter()
        .writeValueAsString(body);

System.out.println("===== ELVA REQUEST JSON =====");
System.out.println(jsonRequest);
System.out.println("URL : "+URL+"/order/");
System.out.println("===== END ELVA REQUEST =====");
                    HttpEntity<String> entity =
                        new HttpEntity<>(mapper.writeValueAsString(body), buildHeaders());

                    ResponseEntity<String> response =
                        getRest().exchange(URL + "/order/", HttpMethod.POST, entity, String.class);

                    int httpCode = response.getStatusCode().value();
                    String resp = response.getBody() == null ? "" : response.getBody().trim();

                    System.out.println("HTTP CODE : " + httpCode);
                    System.out.println("ELVA RESPONSE : " + resp);

                    if (isElvaSuccess(httpCode, resp)) {
                        sukses.add(rs.getString("noorder"));
                    } else {
                        gagal.add(rs.getString("noorder"));
                    }                }

                tampilkanHasil("Kirim Order ELVA", sukses, gagal);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                "Gagal kirim order ELVA\n" + e.getMessage());
        }
    }

    public void kirimRalan(String nopermintaan) {
        kirimOrder(SQL_RALAN, nopermintaan, false);
    }

    public void kirimRanap(String nopermintaan) {
        kirimOrder(SQL_RANAP, nopermintaan, true);
    }

    /* ===============================
       AMBIL URL & HASIL
       =============================== */
    public String AmbilUrl(String acc) {
        return ambilData("/order?acc=", "UrlLink", acc);
    }

    public String AmbilHasil(String acc) {
        return ambilData("/report?acc=", "Report", acc);
    }

    private String ambilData(String path, String field, String acc) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(buildHeaders());
            String resp = getRest()
                .exchange(URL + path + acc, HttpMethod.GET, entity, String.class)
                .getBody();

            JsonNode node = mapper.readTree(resp.replace("[","").replace("]",""));
            return node.path(field).asText();
        } catch (Exception e) {
            JOptionPane.showMessageDialog(null,
                "Gagal ambil data ELVA\n" + e.getMessage());
            return "";
        }
    }

    /* ===============================
       UI RESULT
       =============================== */
    private void tampilkanHasil(String title, List<String> ok, List<String> fail) {
        StringBuilder sb = new StringBuilder();

        if (!ok.isEmpty()) {
            sb.append("Berhasil:\n");
            ok.forEach(o -> sb.append("- ").append(o).append("\n"));
        }
        if (!fail.isEmpty()) {
            sb.append("\nGagal:\n");
            fail.forEach(o -> sb.append("- ").append(o).append("\n"));
        }

        JOptionPane.showMessageDialog(null, sb.toString(), title,
            fail.isEmpty() ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
    }

    /* ===============================
       SQL CONST
       =============================== */
    private static final String SQL_RALAN = "SELECT reg_periksa.no_rkm_medis,pasien.nm_pasien,if(pasien.jk='L','M','F') AS jk,pasien.tgl_lahir,'' AS weight,'I',poliklinik.nm_poli,'' AS dpjp,dokter.nm_dokter,'NW',"+
"'-' AS dep,concat(replace(permintaan_radiologi.noorder,'PR20',''),replace(permintaan_pemeriksaan_radiologi.kd_jenis_prw,'RD','')) AS noorder,permintaan_pemeriksaan_radiologi.kd_jenis_prw,jns_perawatan_radiologi.nm_perawatan,"+
"CONCAT(permintaan_radiologi.tgl_permintaan,' ',if(permintaan_radiologi.jam_permintaan='00:00:00','',permintaan_radiologi.jam_permintaan)) AS tgl_permintaan,"+ "CONCAT(permintaan_radiologi.tgl_sampel,' ',if(permintaan_radiologi.jam_sampel='00:00:00','',permintaan_radiologi.jam_sampel)) AS tgl_sampel,"+
"permintaan_radiologi.diagnosa_klinis,'','','','','',REPLACE(RIGHT(jns_perawatan_radiologi.nm_perawatan,3),']','') as modality,'',0 "+
"FROM permintaan_radiologi "+
"INNER JOIN reg_periksa ON permintaan_radiologi.no_rawat=reg_periksa.no_rawat "+
"INNER JOIN pasien ON reg_periksa.no_rkm_medis=pasien.no_rkm_medis "+
"INNER JOIN dokter ON permintaan_radiologi.dokter_perujuk=dokter.kd_dokter "+
"INNER JOIN poliklinik ON reg_periksa.kd_poli=poliklinik.kd_poli "+
"INNER JOIN penjab ON reg_periksa.kd_pj=penjab.kd_pj "+
"INNER JOIN permintaan_pemeriksaan_radiologi ON permintaan_pemeriksaan_radiologi.noorder=permintaan_radiologi.noorder "+
"INNER JOIN jns_perawatan_radiologi ON permintaan_pemeriksaan_radiologi.kd_jenis_prw=jns_perawatan_radiologi.kd_jenis_prw "+
"WHERE permintaan_radiologi.noorder=? and RIGHT(jns_perawatan_radiologi.nm_perawatan,1)=']'";

    private static final String SQL_RANAP = "SELECT reg_periksa.no_rkm_medis,pasien.nm_pasien,if(pasien.jk='L','M','F') AS jk,pasien.tgl_lahir,'' AS weight,'I',bangsal.nm_bangsal as nm_poli,'' AS dpjp,dokter.nm_dokter,'NW',"+
"'-' AS dep,concat(replace(permintaan_radiologi.noorder,'PR20',''),replace(permintaan_pemeriksaan_radiologi.kd_jenis_prw,'RD','')) AS noorder,permintaan_pemeriksaan_radiologi.kd_jenis_prw,jns_perawatan_radiologi.nm_perawatan,"+
"CONCAT(permintaan_radiologi.tgl_permintaan,' ',if(permintaan_radiologi.jam_permintaan='00:00:00','',permintaan_radiologi.jam_permintaan)) AS tgl_permintaan,"+ "CONCAT(permintaan_radiologi.tgl_sampel,' ',if(permintaan_radiologi.jam_sampel='00:00:00','',permintaan_radiologi.jam_sampel)) AS tgl_sampel,"+
"permintaan_radiologi.diagnosa_klinis,'','','','','',REPLACE(RIGHT(jns_perawatan_radiologi.nm_perawatan,3),']','') as modality,'',0 "+
"FROM permintaan_radiologi "+
"INNER JOIN reg_periksa ON permintaan_radiologi.no_rawat=reg_periksa.no_rawat "+
"INNER JOIN pasien ON reg_periksa.no_rkm_medis=pasien.no_rkm_medis "+
"INNER JOIN dokter ON permintaan_radiologi.dokter_perujuk=dokter.kd_dokter "+
"inner join kamar_inap on reg_periksa.no_rawat=kamar_inap.no_rawat "+
"inner join kamar on kamar_inap.kd_kamar=kamar.kd_kamar "+
"inner join bangsal on kamar.kd_bangsal=bangsal.kd_bangsal "+
"INNER JOIN penjab ON reg_periksa.kd_pj=penjab.kd_pj "+
"INNER JOIN permintaan_pemeriksaan_radiologi ON permintaan_pemeriksaan_radiologi.noorder=permintaan_radiologi.noorder "+
"INNER JOIN jns_perawatan_radiologi ON permintaan_pemeriksaan_radiologi.kd_jenis_prw=jns_perawatan_radiologi.kd_jenis_prw "+
"WHERE permintaan_radiologi.noorder=? and RIGHT(jns_perawatan_radiologi.nm_perawatan,1)=']'";
}
