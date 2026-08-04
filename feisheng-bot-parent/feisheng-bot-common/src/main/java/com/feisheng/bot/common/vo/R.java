package com.feisheng.bot.common.vo;

import java.io.Serializable;

public class R<T> implements Serializable {
    private int code; private String msg; private T data;
    public R() {}
    public static <T> R<T> ok(T data) { R<T> r = new R<>(); r.code=200; r.msg="success"; r.data=data; return r; }
    public static <T> R<T> ok() { return ok(null); }
    public static <T> R<T> fail(int code, String msg) { R<T> r = new R<>(); r.code=code; r.msg=msg; return r; }
    public int getCode() { return code; } public void setCode(int c) { code=c; }
    public String getMsg() { return msg; } public void setMsg(String m) { msg=m; }
    public T getData() { return data; } public void setData(T d) { data=d; }
}