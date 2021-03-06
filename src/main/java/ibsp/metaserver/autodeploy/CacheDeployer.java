 package ibsp.metaserver.autodeploy;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ibsp.metaserver.autodeploy.utils.DeployLog;
import ibsp.metaserver.autodeploy.utils.JschUserInfo;
import ibsp.metaserver.autodeploy.utils.SCPFileUtils;
import ibsp.metaserver.autodeploy.utils.SSHExecutor;
import ibsp.metaserver.bean.DeployFileBean;
import ibsp.metaserver.bean.InstanceBean;
import ibsp.metaserver.bean.InstanceDtlBean;
import ibsp.metaserver.bean.ResultBean;
import ibsp.metaserver.dbservice.CacheService;
import ibsp.metaserver.dbservice.ConfigDataService;
import ibsp.metaserver.dbservice.MetaDataService;
import ibsp.metaserver.eventbus.EventBean;
import ibsp.metaserver.eventbus.EventBusMsg;
import ibsp.metaserver.eventbus.EventType;
import ibsp.metaserver.global.MetaData;
import ibsp.metaserver.monitor.CacheServiceMonitor;
import ibsp.metaserver.utils.CONSTS;
import ibsp.metaserver.utils.FixHeader;
import ibsp.metaserver.utils.HttpUtils;
import ibsp.metaserver.utils.RedisUtils;
import ibsp.metaserver.utils.Topology;
import io.vertx.core.json.JsonObject;
 
public class CacheDeployer implements Deployer {

	private static Logger logger = LoggerFactory.getLogger(CacheDeployer.class);
	
	@Override
	public boolean deployService(String serviceID, String user, String pwd, String sessionKey, ResultBean result) {
		
		List<InstanceDtlBean> nodeClusterList = new LinkedList<InstanceDtlBean>();
		List<InstanceDtlBean> proxyList = new LinkedList<InstanceDtlBean>();
		InstanceDtlBean collectd = new InstanceDtlBean();
		
		if (!CacheService.loadServiceInfo(serviceID, nodeClusterList, proxyList, collectd, result))
			return false;
		
		boolean isServDeployed = MetaData.get().isServDepplyed(serviceID);
		
		//check hash slot
		if (!checkHashSlot(serviceID, nodeClusterList, sessionKey, result))
			return false;
		
		// deploy cache node
		if (!deployNodeClusterList(serviceID, nodeClusterList, sessionKey, result))
			return false;

		// deploy proxy
		if (!deployProxyList(serviceID, proxyList, sessionKey, result))
			return false;
		
		// deploy collectd
		if (!DeployUtils.deployCollectd(serviceID, collectd, sessionKey, result))
			return false;
		
		if (!isServDeployed) {
			// mod t_service.IS_DEPLOYED = 1
			if (!ConfigDataService.modServiceDeployFlag(serviceID, CONSTS.DEPLOYED, result))
				return false;
			DeployUtils.publishDeployEvent(EventType.e21, serviceID);
		}
		
		return true;
	}

	@Override
	public boolean undeployService(String serviceID, String sessionKey, ResultBean result) {
		List<InstanceDtlBean> nodeClusterList = new LinkedList<InstanceDtlBean>();
		List<InstanceDtlBean> proxyList = new LinkedList<InstanceDtlBean>();
		InstanceDtlBean collectd = new InstanceDtlBean();
		
		if (!CacheService.loadServiceInfo(serviceID, nodeClusterList, proxyList, collectd, result))
			return false;
		
		// undeploy collectd
		if (!DeployUtils.undeployCollectd(collectd, sessionKey, true, result))
			return false;
		
		// undeploy proxy
		if (!undeployProxyList(serviceID, proxyList, sessionKey, result))
			return false;
		
		// undeploy cache node
		if (!undeployNodeClusterList(serviceID, nodeClusterList, sessionKey, result))
			return false;
		
		// mod t_service.IS_DEPLOYED = 0
		if (!ConfigDataService.modServiceDeployFlag(serviceID, CONSTS.NOT_DEPLOYED, result))
			return false;
		DeployUtils.publishDeployEvent(EventType.e22, serviceID);
		
		return true;
	}

	@Override
	public boolean deployInstance(String serviceID, String instID,
			String sessionKey, ResultBean result) {
		
		InstanceDtlBean instDtl = MetaDataService.getInstanceDtl(instID, result);
		if (instDtl == null) {
			String err = String.format("instance id:%s not found!", instID);
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo(err);
			return false;
		}
		
		int cmptID = instDtl.getInstance().getCmptID();
		boolean deployRet = false;
		switch (cmptID) {
		case 112:    // cache proxy
			deployRet = deployProxy(serviceID, instDtl, sessionKey, result);
			
			JsonObject paramsJson = new JsonObject();
			paramsJson.put(FixHeader.HEADER_INSTANCE_ID, instID);
			EventBean evBean = new EventBean();
			evBean.setEvType(EventType.e61);
			evBean.setServID(serviceID);
			evBean.setUuid(MetaData.get().getUUID());
			evBean.setJsonStr(paramsJson.toString());
			EventBusMsg.publishEvent(evBean);
			
			break;
		case 110:    // cache node cluster
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo("Sorry, add cache node cluster online is not supported yet...");
			break;
		case 111:    // cache node
			List<InstanceDtlBean> nodeClusterList = new LinkedList<InstanceDtlBean>();
			if (!CacheService.getNodeClustersByServIdOrServiceStub(serviceID, null, nodeClusterList, result)) {
				return false;
			}
				
			InstanceDtlBean cluster = null;
			for (InstanceDtlBean dtl : nodeClusterList) {
				if (dtl.getSubInstances().containsKey(instID)) {
					cluster = dtl;
					break;
				}
			}
			if (cluster==null) {
				result.setRetCode(CONSTS.REVOKE_NOK);
				result.setRetInfo("no cluster contains node "+instID+" found!");
				return false;
			}
			
			if (cluster.getSubInstances().size()==1) {
				//only one node in this cluster and not deployed
				result.setRetCode(CONSTS.REVOKE_NOK);
				result.setRetInfo("Sorry, add cache node cluster online is not supported yet...");
				return false;
			} else if (cluster.getSubInstances().size()>2) {
				//more than one slave is not supported
				result.setRetCode(CONSTS.REVOKE_NOK);
				result.setRetInfo("Sorry, more than one slave is not supported yet...");
				return false;
			} else {
				String masterID = cluster.getAttribute("MASTER_ID").getAttrValue();
				String masterIp = cluster.getSubInstances().get(masterID).getAttribute("IP").getAttrValue();
				String masterPort = cluster.getSubInstances().get(masterID).getAttribute("PORT").getAttrValue();
				
				if (!RedisUtils.setConfigForReplication(masterIp, masterPort))
					return false;
				deployRet = deployCacheNode(serviceID, instDtl, 
						Integer.parseInt(cluster.getAttribute("MAX_MEMORY").getAttrValue()),
						masterIp+" "+masterPort, sessionKey, result);
				CacheServiceMonitor.addReplicationCheckSlave(
						instDtl.getAttribute("IP").getAttrValue()+":"+instDtl.getAttribute("PORT").getAttrValue());
			}
			break;
		case 113:    // CACHE_COLLECTD
			deployRet = DeployUtils.deployCollectd(serviceID, instDtl, sessionKey, result);
			break;
		default:
			break;
		}
		
		return deployRet;
	}

	@Override
	public boolean undeployInstance(String serviceID, String instID,
			String sessionKey, ResultBean result) {
		
		InstanceDtlBean instDtl = MetaDataService.getInstanceDtl(instID, result);
		if (instDtl == null) {
			String err = String.format("instance id:%s not found!", instID);
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo(err);
			return false;
		}
		
		int cmptID = instDtl.getInstance().getCmptID();
		boolean deployRet = false;
		switch (cmptID) {
		case 112:    // cache proxy
			deployRet = undeployProxy(serviceID, instDtl, sessionKey, result);
			
			JsonObject paramsJson = new JsonObject();
			paramsJson.put(FixHeader.HEADER_INSTANCE_ID, instID);
			EventBean evBean = new EventBean();
			evBean.setEvType(EventType.e62);
			evBean.setServID(serviceID);
			evBean.setUuid(MetaData.get().getUUID());
			evBean.setJsonStr(paramsJson.toString());
			EventBusMsg.publishEvent(evBean);
			
			break;
		case 110:    // cache node cluster
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo("Sorry, remove cache node cluster online is not supported yet...");
			break;
		case 111:    // cache node
			List<InstanceDtlBean> nodeClusterList = new LinkedList<InstanceDtlBean>();
			if (!CacheService.getNodeClustersByServIdOrServiceStub(serviceID, null, nodeClusterList, result)) {
				return false;
			}
				
			InstanceDtlBean cluster = null;
			for (InstanceDtlBean dtl : nodeClusterList) {
				if (dtl.getSubInstances().containsKey(instID)) {
					cluster = dtl;
					break;
				}
			}
			if (cluster==null) {
				result.setRetCode(CONSTS.REVOKE_NOK);
				result.setRetInfo("no cluster contains node "+instID+" found!");
				return false;
			}
			
			if (cluster.getSubInstances().size()==1) {
				//only one node in this cluster and not deployed
				result.setRetCode(CONSTS.REVOKE_NOK);
				result.setRetInfo("Sorry, remove cache node cluster online is not supported yet...");
				return false;
			} else if (instID.equals(cluster.getAttribute("MASTER_ID").getAttrValue())) {
				//can not delete master, do switch first
				result.setRetCode(CONSTS.REVOKE_NOK);
				result.setRetInfo("Cannot undeploy master, please do switch first!");
				return false;
			} else {
				deployRet = undeployCacheNode(serviceID, instDtl, sessionKey, result);
			}
			break;
		case 113:    // CACHE_COLLECTD
			deployRet = DeployUtils.undeployCollectd(instDtl, sessionKey, false, result);
			break;
		default:
			break;
		}
		
		return deployRet;
	}

	@Override
	public boolean deleteService(String serviceID, String sessionKey,
			ResultBean result) {
		Topology topo = MetaData.get().getTopo();
		if (topo == null) {
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo("MetaData topo is null!");
			return false;
		}
		
		// delete t_instance,t_instance_attr,t_topology
		Set<String> sub = topo.get(serviceID, CONSTS.TOPO_TYPE_CONTAIN);
		if (sub != null) {
			for (String subID : sub) {
				Set<String> subsub = topo.get(subID, CONSTS.TOPO_TYPE_CONTAIN);
				if (subsub != null) {
					for (String subsubID : subsub) {
						if (!MetaDataService.deleteInstance(subID, subsubID, result))
							return false;
					}
				}
				
				if (!MetaDataService.deleteInstance(serviceID, subID, result))
					return false;
			}
		}
		
		// delete t_instance INST_ID = serviceID
		if (!MetaDataService.deleteInstance(serviceID, serviceID, result))
			return false;
		
		// delete t_service
		if (!MetaDataService.deleteService(serviceID, result))
			return false;
		
		return true;
	}

	@Override
	public boolean deleteInstance(String serviceID, String instID,
			String sessionKey, ResultBean result) {
		return MetaDataService.deleteInstance(serviceID, instID, result);
	}
	
	private boolean checkHashSlot(String serviceID, List<InstanceDtlBean> nodeClusterList, String sessionKey,
			ResultBean result) {
		boolean slotComplete = true, slotEmpty = true;
		for (int i = 0; i < nodeClusterList.size(); i++) {
			InstanceDtlBean clusterDtl = nodeClusterList.get(i);
			String slot = clusterDtl.getAttribute("CACHE_SLOT").getAttrValue();
			if (HttpUtils.isNull(slot)) {
				slotComplete = false;
			} else {
				slotEmpty = false;
			}
		}
		if (slotEmpty) {
			int key = (CONSTS.MAX_CACHE_SLOT+1) / nodeClusterList.size();
			Map<String, String> slots = new HashMap<String, String>();
			for (int i = 0; i < nodeClusterList.size(); i++) {
				InstanceDtlBean clusterDtl = nodeClusterList.get(i);
				String slot = "["+(key*i)+","+(key*(i+1)-1)+"]";
				clusterDtl.setAttribute("CACHE_SLOT", slot);
				slots.put(clusterDtl.getInstID(), slot);
			}
			if (!CacheService.updateHashSlotByCluster(slots, result)) {
				return false;
			} else {
				return true;
			}
		} else if (slotComplete) {
			return true;
		} else {
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo("Cache slot info not compelete...");
			return false;
		}
	}
	
	//deploy and undeploy node cluster list
	private boolean deployNodeClusterList(String serviceID, List<InstanceDtlBean> nodeClusterList, 
			String sessionKey, ResultBean result) {

		for (int i = 0; i < nodeClusterList.size(); i++) {
			InstanceDtlBean clusterDtl = nodeClusterList.get(i);
			if (!deployNodeCluster(serviceID, clusterDtl, sessionKey, result))
				return false;
		}
		return true;
	}
	
	private boolean undeployNodeClusterList(String serviceID, List<InstanceDtlBean> nodeClusterList, 
			String sessionKey, ResultBean result) {

		for (int i = 0; i < nodeClusterList.size(); i++) {
			InstanceDtlBean clusterDtl = nodeClusterList.get(i);
			if (!undeployNodeCluster(serviceID, clusterDtl, sessionKey, result))
				return false;
		}
		return true;
	}
	
	//deploy and undeploy proxy list
	private boolean deployProxyList(String serviceID, List<InstanceDtlBean> proxyList, 
			String sessionKey, ResultBean result) {

		for (int i = 0; i < proxyList.size(); i++) {
			InstanceDtlBean proxyDtl = proxyList.get(i);
			if (!deployProxy(serviceID, proxyDtl, sessionKey, result))
				return false;
		}
		return true;
	}
	
	private boolean undeployProxyList(String serviceID, List<InstanceDtlBean> proxyList, 
			String sessionKey, ResultBean result) {

		for (int i = 0; i < proxyList.size(); i++) {
			InstanceDtlBean proxyDtl = proxyList.get(i);
			if (!undeployProxy(serviceID, proxyDtl, sessionKey, result))
				return false;
		}
		return true;
	}
	
	//deploy and undeploy cache node cluster
	private boolean deployNodeCluster(String serviceID, InstanceDtlBean clusterDtl, 
			String sessionKey, ResultBean result) {
		
		String masterID = clusterDtl.getAttribute("MASTER_ID").getAttrValue();
		if (HttpUtils.isNull(masterID) || masterID.equals("null")) {
			masterID = (String)clusterDtl.getSubInstances().keySet().toArray()[0];
			if (!CacheService.updateMasterID(masterID, clusterDtl.getInstID(), result))
				return false;
		}
		
		int maxMemory = Integer.parseInt(clusterDtl.getAttribute("MAX_MEMORY").getAttrValue());
		
		InstanceDtlBean masterDtl = clusterDtl.getSubInstances().get(masterID);
		String masterAddress = masterDtl.getAttribute("IP").getAttrValue()+" "+masterDtl.getAttribute("PORT").getAttrValue();
		if (!deployCacheNode(serviceID, masterDtl, maxMemory, null, sessionKey, result))
			return false;
		
		for (String instID : clusterDtl.getSubInstances().keySet()) {
			if (instID.equals(masterID))
				continue;
			InstanceDtlBean slaveDtl = clusterDtl.getSubInstances().get(instID);
			if (!deployCacheNode(serviceID, slaveDtl, maxMemory, masterAddress, sessionKey, result))
				return false;
		}
		
		if (!ConfigDataService.modInstanceDeployFlag(clusterDtl.getInstID(), CONSTS.DEPLOYED, result))
			return false;
		DeployUtils.publishDeployEvent(EventType.e23, clusterDtl.getInstID());
		
		return true;
	}
	
	private boolean undeployNodeCluster(String serviceID, InstanceDtlBean clusterDtl, 
			String sessionKey, ResultBean result) {
		
		String masterID = clusterDtl.getAttribute("MASTER_ID").getAttrValue();
		if (HttpUtils.isNull(masterID) || masterID.equals("null")) {
			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo("No master node found in cluster: "+clusterDtl.getInstID());
			return false;
		}
		
		for (String instID : clusterDtl.getSubInstances().keySet()) {
			if (instID.equals(masterID))
				continue;
			InstanceDtlBean slaveDtl = clusterDtl.getSubInstances().get(instID);
			if (!undeployCacheNode(serviceID, slaveDtl, sessionKey, result))
				return false;
		}
		InstanceDtlBean masterDtl = clusterDtl.getSubInstances().get(masterID);
		if (!undeployCacheNode(serviceID, masterDtl, sessionKey, result))
			return false;
		
		if (!ConfigDataService.modInstanceDeployFlag(clusterDtl.getInstID(), CONSTS.NOT_DEPLOYED, result))
			return false;
		DeployUtils.publishDeployEvent(EventType.e24, clusterDtl.getInstID());
		
		return true;
	}
	
	//deploy and undeploy cache node(redis)
	private boolean deployCacheNode(String serviceID, InstanceDtlBean instanceDtl, int maxMemory, String master, 
			String sessionKey, ResultBean result) {
		
		InstanceBean node = instanceDtl.getInstance();
		
		String id    = instanceDtl.getAttribute("CACHE_NODE_ID").getAttrValue();
		String ip    = instanceDtl.getAttribute("IP").getAttrValue();
		String port  = instanceDtl.getAttribute("PORT").getAttrValue();
		String user  = instanceDtl.getAttribute("OS_USER").getAttrValue();
		String pwd   = instanceDtl.getAttribute("OS_PWD").getAttrValue();
		
		if (node.getIsDeployed().equals(CONSTS.DEPLOYED)) {
			String info = String.format("cache node id:%s %s:%s is deployed ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);
			return true;
		}
		
		DeployFileBean proxyFile = MetaData.get().getDeployFile(CONSTS.SERV_CACHE_NODE);
		String deployRootPath = String.format("cache_node_deploy/%s", port);;
		JschUserInfo ui = null;
		SSHExecutor executor = null;
		boolean connected = false;
		
		try {
			String infoBegin = String.format("deploy cache node id:%s %s:%s begin ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, infoBegin);
			
			ui = new JschUserInfo(user, pwd, ip, CONSTS.SSH_PORT_DEFAULT);
			executor = new SSHExecutor(ui);
			executor.connect();
			connected = true;
			
			if (executor.isPortUsed(Integer.parseInt(port))) {
				DeployLog.pubLog(sessionKey, "port "+port+" is already in use......");
				return false;
			}
			
			// make deploy dir and make redis source file
			if (!executor.isDirExistInCurrPath(deployRootPath, sessionKey)) {
				executor.mkdir(deployRootPath, sessionKey);
			}
				
			// fetch and unpack deploy file
			executor.cd("$HOME/" + deployRootPath, sessionKey);
			String srcFile = String.format("%s%s", proxyFile.getFtpDir(), proxyFile.getFileName());
			String desPath = ".";
			executor.scp(proxyFile.getFtpUser(), proxyFile.getFtpPwd(),
					proxyFile.getFtpHost(), srcFile, desPath,
					proxyFile.getSshPort(), sessionKey);
			executor.tgzUnpack(proxyFile.getFileName(), sessionKey);
			executor.rm(proxyFile.getFileName(), false, sessionKey);
				
			//modify redis.conf
			String homeDir = executor.getHome();
			SCPFileUtils scp = new SCPFileUtils(ip, user, pwd, CONSTS.SSH_PORT_DEFAULT);
				
			scp.getFile(homeDir + "/" + deployRootPath + "/conf/" + CONSTS.REDIS_PROPERTIES);
			BufferedReader reader = new BufferedReader(new FileReader("./"+CONSTS.REDIS_PROPERTIES));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				String header = line.split(" ")[0];
				switch (header) {
				case "maxmemory":
					line = line.substring(0, "maxmemory".length()+1)+maxMemory+"gb";
					break;
				case "logfile":
					line = line.substring(0, "logfile".length()+1)+"log_"+port+".log";
					break;
				case "port":
					line = line.substring(0, "port".length()+1)+port;
					break;
				case "dir":
					line = line.substring(0, "dir".length()+1)+homeDir+"/"+deployRootPath+"/data";
					break;
				case "pidfile":
					line = line.substring(0, "pidfile".length()+1)+homeDir+"/"+deployRootPath+"/data/redis"+port+".pid";
					break;
				default:
					break;
				}
				sb.append(line).append("\n");
			}
			if (master != null) {
				sb.append("slaveof ").append(master).append("\n");
			}
			scp.putFile(sb.toString(), CONSTS.REDIS_PROPERTIES, homeDir + "/" + deployRootPath + "/conf");
			reader.close();
			scp.deleteLocalFile(CONSTS.REDIS_PROPERTIES);
			scp.close();
			
			//start redis instance
			executor.execSingleLine("bin/redis-server conf/redis.conf", sessionKey);
			if (!executor.waitProcessStart(port, sessionKey))
				return false;
			
			// mod t_instance.IS_DEPLOYED = 1
			if (!ConfigDataService.modInstanceDeployFlag(id, CONSTS.DEPLOYED, result))
				return false;
			DeployUtils.publishDeployEvent(EventType.e23, id);

			String info = String.format("deploy cache node id:%s %s:%s success ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);

		} catch (Exception e) {
			logger.error(e.getMessage(), e);

			String error = String.format("deploy cache node id:%s %s:%s caught error:%s", id, ip, port, e.getMessage());
			DeployLog.pubErrorLog(sessionKey, error);

			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo(error);

			return false;
		} finally {
			if (connected) {
				executor.close();
			}
		}
		
		return true;
	}
	
	private boolean undeployCacheNode(String serviceID, InstanceDtlBean instanceDtl, 
			String sessionKey, ResultBean result) {
		
		InstanceBean node = instanceDtl.getInstance();
		
		String id    = instanceDtl.getAttribute("CACHE_NODE_ID").getAttrValue();
		String ip    = instanceDtl.getAttribute("IP").getAttrValue();
		String port  = instanceDtl.getAttribute("PORT").getAttrValue();
		String user  = instanceDtl.getAttribute("OS_USER").getAttrValue();
		String pwd   = instanceDtl.getAttribute("OS_PWD").getAttrValue();
		
		if (node.getIsDeployed().equals(CONSTS.NOT_DEPLOYED)) {
			String info = String.format("cache node id:%s %s:%s is not deployed ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);
			return true;
		}
		
		String deployRootPath = String.format("cache_node_deploy/%s", port);;
		JschUserInfo ui = null;
		SSHExecutor executor = null;
		boolean connected = false;
		
		try {
			String infoBegin = String.format("undeploy cache node id:%s %s:%s begin ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, infoBegin);
			
			ui = new JschUserInfo(user, pwd, ip, CONSTS.SSH_PORT_DEFAULT);
			executor = new SSHExecutor(ui);
			executor.connect();
			connected = true;
			
			if (executor.isDirExistInCurrPath(deployRootPath, sessionKey)) {
				executor.cd("$HOME/" + deployRootPath, sessionKey);
				if (executor.isPortUsed(Integer.parseInt(port))) {
					executor.execSingleLine("bin/redis-cli -p "+port+" shutdown", sessionKey);
				}
				if (!executor.waitProcessStop(port, sessionKey))
					return false;
				
				executor.cd("$HOME", sessionKey);
				executor.rm(deployRootPath, true, sessionKey);
			}

			if (!ConfigDataService.modInstanceDeployFlag(id, CONSTS.NOT_DEPLOYED, result))
				return false;
			DeployUtils.publishDeployEvent(EventType.e24, id);

			String info = String.format("undeploy cache node id:%s %s:%s success ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);

		} catch (Exception e) {
			logger.error(e.getMessage(), e);

			String error = String.format("undeploy cache node id:%s %s:%s caught error:%s", id, ip, port, e.getMessage());
			DeployLog.pubErrorLog(sessionKey, error);

			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo(error);

			return false;
		} finally {
			if (connected) {
				executor.close();
			}
		}
		
		return true;
	}
	
	//deploy and undeploy cache proxy
	private boolean deployProxy(String serviceID, InstanceDtlBean instanceDtl,
			String sessionKey, ResultBean result) {
		
		InstanceBean proxy = instanceDtl.getInstance();
		
		String id    = instanceDtl.getAttribute("CACHE_PROXY_ID").getAttrValue();
		String ip    = instanceDtl.getAttribute("IP").getAttrValue();
		String port  = instanceDtl.getAttribute("PORT").getAttrValue();
		String user  = instanceDtl.getAttribute("OS_USER").getAttrValue();
		String pwd   = instanceDtl.getAttribute("OS_PWD").getAttrValue();
		
		if (proxy.getIsDeployed().equals(CONSTS.DEPLOYED)) {
			String info = String.format("proxy id:%s %s:%s is deployed ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);
			return true;
		}
		
		DeployFileBean proxyFile = MetaData.get().getDeployFile(CONSTS.SERV_CACHE_PROXY);
		String deployRootPath = String.format("cache_proxy_deploy/%s", port);
		JschUserInfo ui = null;
		SSHExecutor executor = null;
		boolean connected = false;
		
		try {
			String infoBegin = String.format("deploy cache proxy id:%s %s:%s begin ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, infoBegin);
			
			ui = new JschUserInfo(user, pwd, ip, CONSTS.SSH_PORT_DEFAULT);
			executor = new SSHExecutor(ui);
			executor.connect();
			connected = true;
			
			// deploy jdk
			String rootPath = executor.getHome();
			String jdkRootPath = String.format("%s/jdk_deploy/%s", rootPath, CONSTS.JDK_DEPLOY_PATH);
			if (!executor.isDirExistInCurrPath(jdkRootPath, sessionKey)) {
				executor.mkdir("jdk_deploy", sessionKey);
				executor.cd("jdk_deploy");
				
				DeployFileBean jdkFileBean = MetaData.get().getDeployFile(CONSTS.FILE_TYPE_JDK);
				String jdkFile = String.format("%s%s", jdkFileBean.getFtpDir(), jdkFileBean.getFileName());
				String desPath = ".";
				executor.scp(jdkFileBean.getFtpUser(), jdkFileBean.getFtpPwd(),
						jdkFileBean.getFtpHost(), jdkFile, desPath,
						jdkFileBean.getSshPort(), sessionKey);
				
				executor.tgzUnpack(jdkFileBean.getFileName(), sessionKey);
				executor.rm(jdkFileBean.getFileName(), false, sessionKey);
				
				executor.cd("$HOME/");
			}
			
			
			if (executor.isPortUsed(Integer.parseInt(port))) {
				DeployLog.pubLog(sessionKey, "port "+port+" is already in use......");
				return false;
			}
			
			// make deploy dir
			if (!executor.isDirExistInCurrPath(deployRootPath, sessionKey)) {
				executor.mkdir(deployRootPath, sessionKey);
			}

			executor.cd("$HOME/" + deployRootPath, sessionKey);
			
			// fetch deploy file
			String srcFile = String.format("%s%s", proxyFile.getFtpDir(), proxyFile.getFileName());
			String desPath = ".";
			executor.scp(proxyFile.getFtpUser(), proxyFile.getFtpPwd(),
					proxyFile.getFtpHost(), srcFile, desPath,
					proxyFile.getSshPort(), sessionKey);
			
			// unpack deploy file
			executor.tgzUnpack(proxyFile.getFileName(), sessionKey);
			executor.rm(proxyFile.getFileName(), false, sessionKey);
			
			// modify access.sh and init.properties
			String homeDir = executor.getHome();
			SCPFileUtils scp = new SCPFileUtils(ip, user, pwd, CONSTS.SSH_PORT_DEFAULT);
			
			scp.getFile(homeDir + "/" + deployRootPath + "/bin/" + CONSTS.PROXY_SHELL);
			BufferedReader reader = new BufferedReader(new FileReader("./"+CONSTS.PROXY_SHELL));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.indexOf("COMMAND=")!=-1) {
					line = line.substring(0, "COMMAND=".length())+id;
				}
				sb.append(line).append("\n");
			}
			scp.putFile(sb.toString(), CONSTS.PROXY_SHELL, homeDir + "/" + deployRootPath + "/bin");
			reader.close();
			scp.deleteLocalFile(CONSTS.PROXY_SHELL);
			
			scp.getFile(homeDir + "/" + deployRootPath + "/conf/" + CONSTS.PROXY_PROPERTIES);
			reader = new BufferedReader(new FileReader("./"+CONSTS.PROXY_PROPERTIES));
			sb = new StringBuilder();
			while ((line = reader.readLine()) != null) {
				//if (line.indexOf("JAVA_HOME=")!=-1) {
				//	line = line.substring(0, "JAVA_HOME=".length())+jdkRootPath;
				//}
				if (line.indexOf("proxy.id=")!=-1) {
					line = line.substring(0, "proxy.id=".length())+id;
				}
				if (line.indexOf("metasvr.rooturl=")!=-1) {
					line = line.substring(0, "metasvr.rooturl=".length())+MetaData.get().getMetaServUrls();
				}
				sb.append(line).append("\n");
			}
			scp.putFile(sb.toString(), CONSTS.PROXY_PROPERTIES, homeDir + "/" + deployRootPath + "/conf");
			reader.close();
			scp.deleteLocalFile(CONSTS.PROXY_PROPERTIES);
			scp.close();
			
			//start cache proxy
			executor.cd("./bin", sessionKey);
			
			// sed -i "s/%JDK_ROOT_PATH%/home/g" access.sh
			// replace JDK env
			String repSedPath = jdkRootPath.replaceAll("/", "\\\\/");
			executor.sed(CONSTS.JDK_ROOT_PATH, repSedPath, CONSTS.PROXY_SHELL, sessionKey);
			
			executor.execSingleLine("./"+CONSTS.PROXY_SHELL+" start", sessionKey);
			if (!executor.waitProcessStart(port, sessionKey))
				return false;
			
			// mod t_instance.IS_DEPLOYED = 1
			if (!ConfigDataService.modInstanceDeployFlag(id, CONSTS.DEPLOYED, result))
				return false;
			DeployUtils.publishDeployEvent(EventType.e23, id);

			String info = String.format("deploy cache proxy id:%s %s:%s success ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);

		} catch (Exception e) {
			logger.error(e.getMessage(), e);

			String error = String.format("deploy pd id:%s %s:%s caught error:%s", id, ip, port, e.getMessage());
			DeployLog.pubErrorLog(sessionKey, error);

			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo(error);

			return false;
		} finally {
			if (connected) {
				executor.close();
			}
		}
		
		return true;
	}
	
	private boolean undeployProxy(String serviceID, InstanceDtlBean instanceDtl,
			String sessionKey, ResultBean result) {
		
		InstanceBean proxy = instanceDtl.getInstance();
		
		String id    = instanceDtl.getAttribute("CACHE_PROXY_ID").getAttrValue();
		String ip    = instanceDtl.getAttribute("IP").getAttrValue();
		String port  = instanceDtl.getAttribute("PORT").getAttrValue();
		String user  = instanceDtl.getAttribute("OS_USER").getAttrValue();
		String pwd   = instanceDtl.getAttribute("OS_PWD").getAttrValue();
		
		if (proxy.getIsDeployed().equals(CONSTS.NOT_DEPLOYED)) {
			String info = String.format("proxy id:%s %s:%s is not deployed ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);
			return true;
		}
		
		String deployRootPath = String.format("cache_proxy_deploy/%s", port);
		JschUserInfo ui = null;
		SSHExecutor executor = null;
		boolean connected = false;
		
		try {
			String infoBegin = String.format("undeploy cache proxy id:%s %s:%s begin ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, infoBegin);
			
			ui = new JschUserInfo(user, pwd, ip, CONSTS.SSH_PORT_DEFAULT);
			executor = new SSHExecutor(ui);
			executor.connect();
			connected = true;
			
			if (executor.isDirExistInCurrPath(deployRootPath, sessionKey)) {
				if (executor.isPortUsed(Integer.parseInt(port))) {
					executor.cd("$HOME/" + deployRootPath + "/bin", sessionKey);
					executor.execSingleLine("./access.sh stop", sessionKey);
				}
				if (!executor.waitProcessStop(port, sessionKey))
					return false;
				
				executor.cd("$HOME", sessionKey);
				executor.rm(deployRootPath, true, sessionKey);
			}
			
			if (!ConfigDataService.modInstanceDeployFlag(id, CONSTS.NOT_DEPLOYED, result))
				return false;
			DeployUtils.publishDeployEvent(EventType.e24, id);

			String info = String.format("undeploy cache proxy id:%s %s:%s success ......", id, ip, port);
			DeployLog.pubSuccessLog(sessionKey, info);

		} catch (Exception e) {
			logger.error(e.getMessage(), e);

			String error = String.format("undeploy pd id:%s %s:%s caught error:%s", id, ip, port, e.getMessage());
			DeployLog.pubErrorLog(sessionKey, error);

			result.setRetCode(CONSTS.REVOKE_NOK);
			result.setRetInfo(error);

			return false;
		} finally {
			if (connected) {
				executor.close();
			}
		}
		
		return true;
	}
	
}
