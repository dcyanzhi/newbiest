package com.newbiest.mms.rest.doc.receive;

import com.newbiest.base.msg.Request;
import io.swagger.annotations.ApiModel;
import lombok.Data;

@Data
@ApiModel
public class ReceiveMLotRequest extends Request {

	private static final long serialVersionUID = 1L;
	
	public static final String MESSAGE_NAME = "ReceiveMaterialLot";

	private ReceiveMLotRequestBody body;

}
