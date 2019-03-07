package com.newbiest.mms.state.model;

import com.newbiest.commom.sm.model.Event;

import javax.persistence.DiscriminatorValue;
import javax.persistence.Entity;

/**
 * Created by guoxunbo on 2019/2/12.
 */
@Entity
@DiscriminatorValue(MaterialStatusCategory.CATEGORY_MATERIAL)
public class MaterialEvent extends Event {

    public static final String EVENT_RECEIVE = "Receive";

    public static final String EVENT_REJECT = "Reject";

    public static final String EVENT_STOCK_IN = "StockIn";

    public static final String EVENT_STOCK_OUT = "StockOut";

    public static final String EVENT_PICK = "Pick";

}
