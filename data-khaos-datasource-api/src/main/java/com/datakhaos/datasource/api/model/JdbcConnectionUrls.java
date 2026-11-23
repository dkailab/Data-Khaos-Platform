package com.datakhaos.datasource.api.model;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.datakhaos.common.exception.BusinessException;

import java.util.Locale;
import java.util.regex.Pattern;

/** Connection conventions shared by datasource probing and DB-Sync. */
public final class JdbcConnectionUrls {
    private static final Pattern IDENTIFIER = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_$#]*");

    private JdbcConnectionUrls() {}

    public static String hive(String address, String database, String properties) {
        String auth = StrUtil.isBlank(properties) ? null : JSONUtil.parseObj(properties).getStr("hiveAuth");
        String url = "jdbc:hive2://" + address + "/" + StrUtil.blankToDefault(database, "default");
        if (StrUtil.isBlank(auth) || "NONE".equalsIgnoreCase(auth)) return url;
        if ("NOSASL".equalsIgnoreCase(auth)) return url + ";auth=noSasl";
        throw new BusinessException("Hive hiveAuth 支持 NONE 或 NOSASL");
    }

    public static String dameng(String address, String schema) {
        return "jdbc:dm://" + address + (StrUtil.isBlank(schema) ? "" : "?schema=" + damengIdentifier(schema));
    }

    public static String damengIdentifier(String value) {
        if (StrUtil.isBlank(value) || !IDENTIFIER.matcher(value).matches()) {
            throw new BusinessException("达梦模式和表名须使用常规未加引号的标识符");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
