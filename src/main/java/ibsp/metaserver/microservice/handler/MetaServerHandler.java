package ibsp.metaserver.microservice.handler;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ibsp.metaserver.annotation.App;
import ibsp.metaserver.annotation.Service;
import ibsp.metaserver.bean.MetaAttributeBean;
import ibsp.metaserver.dbservice.MetaDataService;
import ibsp.metaserver.eventbus.EventBean;
import ibsp.metaserver.eventbus.EventBusMsg;
import ibsp.metaserver.eventbus.EventType;
import ibsp.metaserver.global.MetaData;
import ibsp.metaserver.utils.CONSTS;
import ibsp.metaserver.utils.FixHeader;
import ibsp.metaserver.utils.HttpUtils;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.SocketAddress;
import io.vertx.ext.web.RoutingContext;

@App(path = "/metasvr")
public class MetaServerHandler {
	
	private static Logger logger = LoggerFactory.getLogger(MetaServerHandler.class);
	
	@Service(id = "test", name = "test", auth = false, bwswitch = false)
	public static void test(RoutingContext routeContext) {
		HttpServerRequest  req  = routeContext.request();
		
		JsonObject json = new JsonObject();
		
		if (req != null) {
			SocketAddress remoteAddr = req.remoteAddress();
			SocketAddress localAddr  = req.localAddress();
			
			json.put(FixHeader.HEADER_RET_CODE,    CONSTS.REVOKE_OK);
			json.put(FixHeader.HEADER_RET_INFO,    "");
			json.put(FixHeader.HEADER_REMOTE_IP,   remoteAddr.host());
			json.put(FixHeader.HEADER_REMOTE_PORT, remoteAddr.port());
			json.put(FixHeader.HEADER_LOCAL_IP,    localAddr.host());
			json.put(FixHeader.HEADER_LOCAL_PORT,  localAddr.port());
			
			logger.debug("respond:{}", json.toString());
		} else {
			json.put(FixHeader.HEADER_RET_CODE,    CONSTS.REVOKE_NOK);
			json.put(FixHeader.HEADER_RET_INFO,    "HttpServerRequest null.");
		}
		
		HttpUtils.outJsonObject(routeContext, json);
	}
	
	@Service(id = "testDB", name = "testDB", auth = false, bwswitch = false)
	public static void testDB(RoutingContext routeContext) {
		HttpServerRequest  req  = routeContext.request();
		JsonObject json = new JsonObject();
		
		if (req != null) {
			boolean ret = MetaDataService.testDB();
			json.put(FixHeader.HEADER_RET_CODE,    ret ? CONSTS.REVOKE_OK : CONSTS.REVOKE_NOK);
			json.put(FixHeader.HEADER_RET_INFO,    ret ? "" : "db query error!");
		} else {
			json.put(FixHeader.HEADER_RET_CODE,    CONSTS.REVOKE_NOK);
			json.put(FixHeader.HEADER_RET_INFO,    "HttpServerRequest null.");
		}
		
		HttpUtils.outJsonObject(routeContext, json);
	}
	
	@Service(id = "reloadMetaData", name = "reloadMetaData", auth = true, bwswitch = true)
	public static void reloadMetaData(RoutingContext routeContext) {
		MetaData.get().reloadMetaData();
		
		JsonObject json = new JsonObject();
		json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_OK);
		HttpUtils.outJsonObject(routeContext, json);
	}
	
	@Service(id = "getCmptAttrByType", name = "getCmptAttrByType", auth = true, bwswitch = true)
	public static void getCmptAttrByType(RoutingContext routeContext) {
		JsonObject json = new JsonObject();
		
		Map<String, String> params = HttpUtils.getParamForMap(routeContext);
		if(params == null) {
			json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
			json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_PARAM_INCOMPLETE);
		} else {
			String cmptType  = params != null ? params.get(FixHeader.HEADER_CMPT_TYPE) : null;
			if (!HttpUtils.isNotNull(cmptType)) {
				json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
				json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_PARAM_INCOMPLETE);
			} else {
				List<MetaAttributeBean> attrs = MetaData.get().getCmptAttrByName(cmptType);
				if (attrs == null || attrs.size() == 0) {
					json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
					json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_METADATA_NOT_FOUND);
				} else {
					json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_OK);
					
					JsonArray arr = new JsonArray();
					for (MetaAttributeBean attr : attrs) {
						arr.add(attr.asJson());
					}
					json.put(FixHeader.HEADER_RET_INFO, arr);
				}
			}
		}
		
		HttpUtils.outJsonObject(routeContext, json);
	}
	
	@Service(id = "getCmptAttrByID", name = "getCmptAttrByID", auth = true, bwswitch = true)
	public static void getCmptAttrByID(RoutingContext routeContext) {
		JsonObject json = new JsonObject();
		
		Map<String, String> params = HttpUtils.getParamForMap(routeContext);
		if(params == null) {
			json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
			json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_PARAM_INCOMPLETE);
		} else {
			String cmptType  = params != null ? params.get(FixHeader.HEADER_CMPT_ID) : null;
			if (!HttpUtils.isNotNull(cmptType)) {
				json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
				json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_PARAM_INCOMPLETE);
			} else {
				List<MetaAttributeBean> attrs = MetaData.get().getCmptAttrByID(Integer.valueOf(cmptType));
				if (attrs == null || attrs.size() == 0) {
					json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
					json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_METADATA_NOT_FOUND);
				} else {
					json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_OK);
					
					JsonArray arr = new JsonArray();
					for (MetaAttributeBean attr : attrs) {
						arr.add(attr.asJson());
					}
					json.put(FixHeader.HEADER_RET_INFO, arr);
				}
			}
		}
		
		HttpUtils.outJsonObject(routeContext, json);
	}
	
	@Service(id = "putClientStatisticInfo", name = "putClientStatisticInfo")
	public static void putClientStatisticInfo(RoutingContext routeContext) {
		JsonObject json = new JsonObject();
		
		Map<String, String> params = HttpUtils.getParamForMap(routeContext);
		String clientType = params != null ? params.get(FixHeader.HEADER_CLIENT_TYPE) : null;
		String clientInfo = params != null ? params.get(FixHeader.HEADER_CLIENT_INFO) : null;
		String lsnrAddr = params != null ? params.get(FixHeader.HEADER_LSNR_ADDR) : null;
		if (!HttpUtils.isNotNull(clientType) || !HttpUtils.isNotNull(clientInfo) || !HttpUtils.isNotNull(lsnrAddr)) {
			json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_NOK);
			json.put(FixHeader.HEADER_RET_INFO, CONSTS.ERR_PARAM_INCOMPLETE);
		} else {
			//通知集群节点及时更新内存数据
			JsonObject paramsJson = new JsonObject();
			paramsJson.put(FixHeader.HEADER_CLIENT_TYPE, clientType);
			paramsJson.put(FixHeader.HEADER_CLIENT_INFO, clientType);
			paramsJson.put(FixHeader.HEADER_LSNR_ADDR, lsnrAddr);
			
			EventBean evBean = new EventBean();
			evBean.setEvType(EventType.e98);
			evBean.setJsonStr(paramsJson.toString());
			EventBusMsg.publishEvent(evBean);
			
			json.put(FixHeader.HEADER_RET_CODE, CONSTS.REVOKE_OK);
			json.put(FixHeader.HEADER_RET_INFO, "");
		}
		
		HttpUtils.outJsonObject(routeContext, json);
	}
}
