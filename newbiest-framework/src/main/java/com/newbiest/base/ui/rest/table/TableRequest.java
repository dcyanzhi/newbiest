package com.newbiest.base.ui.rest.table;

import com.newbiest.msg.Request;
import lombok.Data;

/**
 * Created by guoxunbo on 2018/7/26.
 */
@Data
public class TableRequest extends Request {

    public static final String MESSAGE_NAME = "TableManage";

    public static final String ACTION_GET_BY_AUTHORITY = "GetByAuthority";
    public static final String ACTION_GET_DATA = "GetData";
    public static final String ACTION_GET_BY_NAME = "GetByName";
    public static final String ACTION_EXP_TEMPLATE = "ExpTemplate";
    public static final String ACTION_EXP_DATA = "ExpData";
    public static final String ACTION_IMPORT = "Import";

    private TableRequestBody body;

}
