package com.stalary.pf.recruit.exception;

import lombok.Getter;

/**
 * @author Stalary
 * @description
 * @date 2018/03/24
 */
public enum ResultEnum {
    UNKNOW_ERROR(500, "服务器错误"),

    // 1开头为用户有关的错误
    NEED_LOGIN(1001, "未登陆"),

    RECRUIT_NOT_EXIST(1601, "招聘信息不存在"),

    SUCCESS(0, "成功");

    @Getter
    private Integer code;

    @Getter
    private String msg;

    ResultEnum(Integer code, String msg) {
        this.code = code;
        this.msg = msg;
    }

}
