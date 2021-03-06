package com.newbiest.mms.rest.doc.issue.material;

import com.newbiest.base.msg.Request;
import io.swagger.annotations.ApiModel;
import lombok.Data;

@Data
@ApiModel
public class IssueMLotByDocLineRequest extends Request {

	private static final long serialVersionUID = 1L;
	
	public static final String MESSAGE_NAME = "IssueMLotByDocLine";

	public static final String ACTION_TYPE_ISSUE = "Issue";
	public static final String ACTION_TYPE_VALIDATION = "Validation";

	private IssueMLotByDocLineRequestBody body;

}
