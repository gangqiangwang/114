package code.jtduan.ticket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.LocalTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static code.jtduan.ticket.TConfig.*;

/**
 * @author jtduan
 * @date 2016/12/8
 */
@Service
public class TService {

    @Value("${sessionid}")
    public String sessionid;

    @Value("${bigip}")
    public String bIGipServerotn;

    @Value("${date}")
    public String date;

    @Value("${verifyPic}")
    public String verifyPic;

    /**
     * 程序运行中需要获取的参数
     */
    private String globalRepeatSubmitToken = "";
    private String key_check_isChange = "";
    private String leftTicketStr = "";

    private List<Train> find_trains;
    private Train cur_train;

    private String passengerTicketStr = "";
    private String oldPassengerStr = "";

    private String siteTypeStr = "";

    private OkHttpClient client;

    public boolean inited = false;


    public void run() {
        if (!inited) return;
        LocalTime start = LocalTime.now();
        System.out.println("[Task start]:" + start);
        while (LocalTime.now().compareTo(start.plusMinutes(2)) < 0) {
            try {
                System.out.print(".");
                if (!queryTicket()) continue;
                System.out.print("[find Tickets]");
                for (int j = 0; j < 10; j++) {
                    for (Train temp : find_trains) {
                        cur_train = temp;
                        if (!submitOrder(cur_train.secret)) {
                            continue;
                        }
                        if (!checkOrderInfo()) {
                            continue;
                        }
                        if (confirmTicket()) {
                            System.out.println("[order success]");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("[System Error]");
            }
        }
    }

    private void getPassangers() {
        sendPost("https://kyfw.12306.cn/otn/confirmPassenger/getPassengerDTOs","_json_att=&REPEAT_SUBMIT_TOKEN="+globalRepeatSubmitToken);
    }

    private void checkUser() {
        sendPost("https://kyfw.12306.cn/otn/login/checkUser","_json_att=");
    }

    public synchronized boolean init() {
        System.out.println("===========");
        System.out.println("date:" + date);
        System.out.println("train:" + train);
        System.out.println("start:" + start);
        System.out.println("end:" + end);
        System.out.println("cardId:" + cardId);
        System.out.println("siteType:" + siteType);
        System.out.println("===========");
        find_trains = new ArrayList<>();

        Scanner cin = new Scanner(System.in);
        if (sessionid.isEmpty()) {
            System.out.println("cin the SessionId:");
            sessionid = cin.nextLine();
        }
        if (bIGipServerotn.isEmpty()) {
            System.out.println("cin the bIGipServerotn:");
            bIGipServerotn = cin.nextLine();
        }
        MyCookieJar cookieJar = new MyCookieJar(sessionid, bIGipServerotn);
        client = HttpsUtil.getUnsafeOkHttpClient().newBuilder()
                .cookieJar(cookieJar)
                .build();
        passengerTicketStr = siteType + ",0,1," + realName + ",1," + cardId + ",,N";
        oldPassengerStr = realName + ",1," + cardId + ",1_";

        if (siteType.equals("O")) {
            siteTypeStr = "ze_num";
        } else if (siteType.equals("3")) {
            siteTypeStr = "yw_num";
        } else {
            System.out.println(" invalid siteType ");
            return false;
        }
        inited = true;
        if (!keepSession()) {
            System.out.println("[Not Logined]");
            System.exit(-1);
        }
        return true;
    }

    private boolean queryTicket() {
        find_trains.clear();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("leftTicketDTO.train_date", date);
        params.put("leftTicketDTO.from_station", start);
        params.put("leftTicketDTO.to_station", end);
        params.put("purpose_codes", "ADULT");

        String url = "https://kyfw.12306.cn/otn/leftTicket/queryA";
        String res = sendGet(url, buildParams(params));
        if (res.contains("拒绝访问")) {
            System.out.print("[denied]");
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            return false;
        }
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node;
        try {
            node = objectMapper.readTree(res);
        } catch (IOException e) {
            System.out.println("[" + res + "]");
            return false;
        }
        if (node.get("data") == null) {
            System.out.println("[" + res + "]");
            return false;
        }
        for (JsonNode temp : node.get("data")) {
            JsonNode n = temp.get("queryLeftNewDTO");
            if (train.contains(n.get("station_train_code").asText()) &&
                    !(("--").equals(n.get(siteTypeStr).asText()) || ("无").equals(n.get(siteTypeStr).asText()))) {
                Train t = new Train();
                t.secret = temp.get("secretStr").asText();
                t.train_location = n.get("location_code").asText();
                t.train_no = n.get("train_no").asText();
                t.from_station_telecode = n.get("from_station_telecode").asText();
                t.to_station_telecode = n.get("to_station_telecode").asText();
                t.from_station_name = n.get("from_station_name").asText();
                t.to_station_name = n.get("to_station_name").asText();
                find_trains.add(t);
            }
        }
        return !find_trains.isEmpty();
    }

    private boolean submitOrder(String secret) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("secretStr", secret);
        params.put("train_date", date);
        params.put("tour_flag", "dc");
        params.put("purpose_codes", "ADULT");
        params.put("query_from_station_name", cur_train.from_station_name);
        params.put("query_to_station_name", cur_train.to_station_name);
        String temp = sendPost("https://kyfw.12306.cn/otn/leftTicket/submitOrderRequest", buildParams(params));

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node;
        try {
            node = objectMapper.readTree(temp);
            if (node.get("status").asText().equals("false")) {
                System.out.println(node.get("messages").get(0).asText());
                return false;
            }
        } catch (IOException e) {
            System.out.println("[submitOrder]:" + temp);
            return false;
        }

        String res = sendPost("https://kyfw.12306.cn/otn/confirmPassenger/initDc", "_json_att=", 1);
        Pattern p = Pattern.compile("globalRepeatSubmitToken {1,3}= {1,3}'(.+?)'");
        Matcher matcher = p.matcher(res);
        if (matcher.find()) {
            globalRepeatSubmitToken = matcher.group(1);
        }

        Pattern p2 = Pattern.compile("'key_check_isChange':'(.+?)'");
        Matcher matcher2 = p2.matcher(res);
        if (matcher2.find()) {
            key_check_isChange = matcher2.group(1);
        }

        Pattern p3 = Pattern.compile("'leftTicketStr':'(.+?)'");
        Matcher matcher3 = p3.matcher(res);
        if (matcher3.find()) {
            leftTicketStr = matcher3.group(1);
        }

        if (globalRepeatSubmitToken.isEmpty() || key_check_isChange.isEmpty() || leftTicketStr.isEmpty()) return false;
        return true;
    }

    private boolean checkOrderInfo() {
        String url = "https://kyfw.12306.cn/otn/confirmPassenger/checkOrderInfo";
        Map<String, String> params = new LinkedHashMap<>();
        params.put("cancel_flag", "2");
        params.put("bed_level_order_num", "000000000000000000000000000000");
        params.put("passengerTicketStr", passengerTicketStr);
        params.put("oldPassengerStr", oldPassengerStr);
        params.put("tour_flag", "dc");
        params.put("randCode", "");
        params.put("_json_att", "");
        params.put("REPEAT_SUBMIT_TOKEN", globalRepeatSubmitToken);
        String str = sendPost(url, buildParams(params));

        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node;
        try {
            node = objectMapper.readTree(str);
        } catch (IOException e) {
            System.out.println("[checkOrderInfo]:" + str);
            return false;
        }
        if (node.get("data").get("submitStatus").asText().equals("true")) {
            return true;
        } else {
            System.out.println("[checkOrderInfo]:" + node.get("data").get("errMsg"));
            return false;
        }
    }

    @Deprecated
    private void getQueueCount() {
        SimpleDateFormat sdf = new SimpleDateFormat("E MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US);
        String url = "https://kyfw.12306.cn/otn/confirmPassenger/getQueueCount";
        Map<String, String> params = new LinkedHashMap<>();

        try {
            params.put("train_date", sdf.format(new SimpleDateFormat("yyyy-MM-dd").parse(date)));
        } catch (ParseException e) {
            e.printStackTrace();
        }
        params.put("train_no", cur_train.train_no);
        params.put("stationTrainCode", train);
        params.put("seatType", siteType);
        params.put("fromStationTelecode", cur_train.from_station_telecode);
        params.put("toStationTelecode", cur_train.to_station_telecode);
        params.put("leftTicket", leftTicketStr);
        params.put("purpose_codes", "00");
        params.put("train_location", cur_train.train_location);
        params.put("_json_att", "");
        params.put("REPEAT_SUBMIT_TOKEN", globalRepeatSubmitToken);
        String res = sendPost(url, buildParams(params));
        System.out.println("[getQueueCount]" + res);
    }

    private boolean confirmTicket() {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("passengerTicketStr", passengerTicketStr);
        params.put("oldPassengerStr", oldPassengerStr);
        params.put("randCode", "");
        params.put("purpose_codes", "00");
        params.put("key_check_isChange", key_check_isChange);
        params.put("leftTicketStr", leftTicketStr);
        params.put("train_location", cur_train.train_location);
        params.put("choose_seats", "");
        params.put("seatDetailType", "000");
        params.put("roomType", "00");
        params.put("dwAll", "N");
        params.put("_json_att", "");
        params.put("REPEAT_SUBMIT_TOKEN", globalRepeatSubmitToken);
        String res = sendPost("https://kyfw.12306.cn/otn/confirmPassenger/confirmSingleForQueue", buildParams(params));
        System.out.println("[results]:" + res);
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode node;
        try {
            node = objectMapper.readTree(res);
        } catch (IOException e) {
            return false;
        }
        return node.get("status").asText().equals("true") && node.get("data").get("submitStatus").asText().equals("true");
    }

    private String sendPost(String url, String params) {
        return sendPost(url, params, 2);
    }

    private String sendPost(String url, String params, int num) {
        RequestBody body = RequestBody.create(MediaType.parse("application/x-www-form-urlencoded; charset=utf-8"), params);
        Request request = new Request.Builder()
                .url(url).post(body)
                .build();

        return send(request, num);
    }

    private String sendGet(String url, String params) {
        if (!params.isEmpty()) {
            url = url + "?" + params;
        }
        Request request = new Request.Builder()
                .url(url).get()
                .build();
        return send(request, 2);
    }

    private String send(Request request, int num) {
        try {
            Response response = client.newCall(request).execute();
            return response.body().string();
        } catch (IOException e) {
            if (num > 1) {
                return send(request, num - 1);
            } else {
                System.out.println(e.getMessage());
                return "";
            }
        }
    }

    private String buildParams(Map<String, String> map) {
        StringBuilder sb = new StringBuilder();
        for (String str : map.keySet()) {
            sb.append(str).append("=").append(map.get(str)).append("&");
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    @Deprecated
    public String getverifyCodeString() {
        if (verifyPic.isEmpty()) return "";
        String[] pics = verifyPic.split("[ ,]");
        StringBuilder sb = new StringBuilder();
        for (String pic : pics) {
            int x = 70 * (pic.charAt(0) - '0' - 1) + 35;
            int y = 70 * (pic.charAt(1) - '0' - 1) + 35;
            sb.append(y).append(",").append(x).append(",");
        }
        sb.deleteCharAt(sb.length() - 1);
        return sb.toString();
    }

    private boolean keepSession() {
        if (!inited) {
            return false;
        }
        String res = sendGet("https://kyfw.12306.cn/otn/modifyUser/initQueryUserInfo", "");
        if (res.contains(realName)) {
            System.out.println(LocalTime.now() + "===session valid===");
            return true;
        } else {
            System.out.println(LocalTime.now() + "===session Invalid===");
            return false;
        }
    }

    private class MyCookieJar implements CookieJar {

        private List<Cookie> cookies;

        MyCookieJar(String sessionid, String bigip) {
            cookies = new ArrayList<>();
            Cookie cookie = new Cookie.Builder()
                    .name("current_captcha_type").value("Z").domain("kyfw.12306.cn")
                    .build();
            Cookie cookie2 = new Cookie.Builder()
                    .name("JSESSIONID").value(sessionid).domain("kyfw.12306.cn").path("/otn")
                    .build();

            Cookie cookie3 = new Cookie.Builder()
                    .name("BIGipServerotn").value(bigip).domain("kyfw.12306.cn")
                    .build();

            this.cookies.add(cookie);
            this.cookies.add(cookie2);
            this.cookies.add(cookie3);
        }

        @Override
        public void saveFromResponse(HttpUrl httpUrl, List<Cookie> list) {
        }

        @Override
        public List<Cookie> loadForRequest(HttpUrl httpUrl) {
            if (cookies == null) return Collections.emptyList();
            return cookies;
        }
    }

    class Train {
        public String train_no;
        public String from_station_telecode;
        public String to_station_telecode;
        public String train_location;
        public String secret;
        public String from_station_name;
        public String to_station_name;
    }
}