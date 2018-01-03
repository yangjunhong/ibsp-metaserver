package ibsp.metaserver.global;

import ibsp.metaserver.bean.CollectQuotaBean;
import ibsp.metaserver.bean.DeployFileBean;
import ibsp.metaserver.bean.IdSetBean;
import ibsp.metaserver.bean.InstAttributeBean;
import ibsp.metaserver.bean.InstanceBean;
import ibsp.metaserver.bean.InstanceDtlBean;
import ibsp.metaserver.bean.MetaAttributeBean;
import ibsp.metaserver.bean.MetaComponentBean;
import ibsp.metaserver.bean.RelationBean;
import ibsp.metaserver.bean.ServiceBean;
import ibsp.metaserver.bean.TopologyBean;
import ibsp.metaserver.dbservice.MetaDataService;
import ibsp.metaserver.eventbus.EventType;
import ibsp.metaserver.utils.CONSTS;
import ibsp.metaserver.utils.HttpUtils;
import ibsp.metaserver.utils.SysConfig;
import ibsp.metaserver.utils.Topology;
import ibsp.metaserver.utils.UUIDUtils;
import io.vertx.core.json.JsonObject;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

// TODO t_service EVENT 
// TODO t_instance EVENT
// TODO t_instance_attr EVENT
// TODO t_topology EVENT

public class MetaData {
	
	private static Logger logger = LoggerFactory.getLogger(MetaData.class);
	
	private static String ID_INDEX = "_ID";
	
	private String uuid;
	
	private Map<Integer, MetaAttributeBean> metaAttrMap;
	private Map<Integer, MetaComponentBean> metaCmptMap;
	private Map<String,  Integer> metaCmptName2IDMap;
	private Map<Integer, IdSetBean<Integer>> metaCmpt2AttrMap;
	private Map<String, DeployFileBean> deployFileMap;
	private Map<String, ServiceBean> serviceMap;
	private Map<String, InstanceDtlBean> instanceDtlMap;
	private Map<String, Integer> collectQuotaMap;
	private Topology topo;
	
	private JedisPool jedisPool;
	
	private static MetaData theInstance = null;
	private static ReentrantLock intanceLock = null;
	
	static {
		intanceLock = new ReentrantLock();
	}
	
	public MetaData() {
		uuid               = UUIDUtils.genUUID();
		
		metaAttrMap        = new ConcurrentHashMap<Integer, MetaAttributeBean>();
		metaCmptMap        = new ConcurrentHashMap<Integer, MetaComponentBean>();
		metaCmptName2IDMap = new ConcurrentHashMap<String,  Integer>();
		metaCmpt2AttrMap   = new ConcurrentHashMap<Integer, IdSetBean<Integer>>();
		deployFileMap      = new ConcurrentHashMap<String,  DeployFileBean>();
		serviceMap         = new ConcurrentHashMap<String,  ServiceBean>();
		instanceDtlMap     = new ConcurrentHashMap<String,  InstanceDtlBean>();
		collectQuotaMap    = new ConcurrentHashMap<String,  Integer>();
		topo               = new Topology();
	}
	
	public boolean doTopo(JsonObject json, EventType type) {
		boolean res = true;
		
		if (topo == null)
			return false;

		String instID1 = json.getString("INST_ID1");
		String instID2 = json.getString("INST_ID2");
		Integer topoType = json.getInteger("TOPO_TYPE");

		if (HttpUtils.isNull(instID1) || HttpUtils.isNull(instID2)
				|| topoType == null)
			return false;

		switch (type) {
		case e1:
			topo.put(instID1, instID2, topoType);
			break;
		case e2:
			topo.remove(instID1, instID2, topoType);
			break;
		default:
			res = false;
			break;
		}

		return res;
	}
	
	public boolean doInstance(JsonObject json, EventType type) {
		boolean res = true;
		
		if (instanceDtlMap == null)
			return false;
		
		String instID = json.getString("INST_ID");
		if (HttpUtils.isNull(instID))
			return false;
		
		switch (type) {
		case e3:
		case e4:
			InstanceDtlBean instDtl = MetaDataService.getInstanceDtl(instID);
			if (instDtl != null) {
				instanceDtlMap.put(instID, instDtl);
			}
			break;
		case e5:
			instanceDtlMap.remove(instID);
			break;
		default:
			res = false;
			break;
		}
		
		return res;
	}
	
	public boolean doService(JsonObject json, EventType type) {
		boolean res = true;
		
		if (serviceMap == null)
			return false;
		
		String instID = json.getString("INST_ID");
		if (HttpUtils.isNull(instID))
			return false;
		
		switch (type) {
		case e6:
		case e7:
			ServiceBean service = MetaDataService.getService(instID);
			if (service != null) {
				serviceMap.put(instID, service);
			}
			break;
		case e8:
			serviceMap.remove(instID);
			break;
		default:
			res = false;
			break;
		}
		
		return res;
	}
	
	public static MetaData get() {
		try {
			intanceLock.lock();
			if (theInstance != null){
				return theInstance;
			} else {
				theInstance = new MetaData();
				theInstance.init();
			}
		} finally {
			intanceLock.unlock();
		}
		
		return theInstance;
	}
	
	private void init() {
		initData();
		initJedisPool();
	}
	
	private void initData() {
		LoadMetaAttr();
		LoadMetaCmpt();
		LoadMetaCmpt2Attr();
		LoadDeployFile();
		LoadServices();
		LoadInstances();
		LoadCollectQuota();
		LoadTopo();
	}
	
	private void initJedisPool() {
		JedisPoolConfig jedisConfig = new JedisPoolConfig();
		jedisConfig.setMaxIdle(SysConfig.get().getRedisPoolSize());
		jedisConfig.setMaxWaitMillis(10000);
		jedisConfig.setTestOnBorrow(true);
		
		jedisPool = new JedisPool(jedisConfig, SysConfig.get().getRedisHost(), SysConfig.get().getRedisPort(), 3000, SysConfig.get().getRedisAuth());
	}
	
	public String getUUID() {
		return uuid;
	}
	
	public Jedis getJedis() {
		Jedis jedis = null;
		try {
			intanceLock.lock();
			
			jedis = jedisPool.getResource();
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
		return jedis;
	}
	
	private void LoadCollectQuota() {
		try {
			intanceLock.lock();
			
			List<CollectQuotaBean> list = MetaDataService.getAllCollectQuotas();
			if (list == null || list.isEmpty())
				return;
			
			collectQuotaMap.clear();
			
			for (CollectQuotaBean quota : list) {
				if (quota == null)
					continue;
				
				collectQuotaMap.put(quota.getQuotaName(), quota.getQuotaCode());
			}
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadServices() {
		try {
			intanceLock.lock();
			
			List<ServiceBean> list = MetaDataService.getAllDeployedServices();
			if (list == null || list.isEmpty())
				return;
			
			serviceMap.clear();
			
			for (ServiceBean service : list) {
				if (service == null)
					continue;
				
				serviceMap.put(service.getInstID(), service);
			}
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadInstances() {
		try {
			intanceLock.lock();
			
			List<InstanceBean> instances = MetaDataService.getAllInstance();
			List<InstAttributeBean> instAttrs = MetaDataService.getAllInstanceAttribute();
			
			if (instances == null || instances.isEmpty())
				return;
			
			if (instAttrs == null || instAttrs.isEmpty())
				return;
			
			instanceDtlMap.clear();
			
			for (InstanceBean instance : instances) {
				if (instance == null)
					continue;
				
				InstanceDtlBean instDtl = new InstanceDtlBean(instance);
				instanceDtlMap.put(instDtl.getInstID(), instDtl);
			}
			
			for (InstAttributeBean instAttr : instAttrs) {
				if (instAttr == null)
					continue;
				
				String instID = instAttr.getInstID();
				InstanceDtlBean instDtl = instanceDtlMap.get(instID);
				if (instDtl == null) {
					String err = String.format("instID:%s not found!", instID);
					logger.error(err);
					continue;
				}
				
				instDtl.addAttribute(instAttr);
			}
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadTopo() {
		try {
			intanceLock.lock();
			
			List<TopologyBean> list = MetaDataService.getAllTopology();
			if (list == null || list.isEmpty())
				return;
			
			topo.clear();
			
			for (TopologyBean topoBean : list) {
				if (topoBean == null)
					continue;
				
				topo.put(topoBean.getInstID1(), topoBean.getInstID2(), topoBean.getTopoType());
			}
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadMetaAttr() {
		try {
			intanceLock.lock();
			
			List<MetaAttributeBean> metaAttrList = MetaDataService.getAllMetaAttribute();
			if (metaAttrList == null) {
				return;
			}
			
			metaAttrMap.clear();
			
			for (MetaAttributeBean metaAttr : metaAttrList) {
				if (metaAttr == null)
					continue;
				
				metaAttrMap.put(metaAttr.getAttrID(), metaAttr);
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadMetaCmpt() {
		try {
			intanceLock.lock();
			
			List<MetaComponentBean> metaCmptList = MetaDataService.getAllMetaComponent();
			if (metaCmptList == null) {
				return;
			}
			
			metaCmptMap.clear();
			metaCmptName2IDMap.clear();
			
			for (MetaComponentBean metaCmpt : metaCmptList) {
				if (metaCmpt == null)
					continue;
				
				metaCmptMap.put(metaCmpt.getCmptID(), metaCmpt);
				metaCmptName2IDMap.put(metaCmpt.getCmptName(), metaCmpt.getCmptID());
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadMetaCmpt2Attr() {
		try {
			intanceLock.lock();
			
			List<RelationBean> cmpt2AttrList = MetaDataService.getAllCmpt2Attr();
			if (cmpt2AttrList == null) {
				return;
			}
			
			metaCmpt2AttrMap.clear();
			
			for (RelationBean relationBean : cmpt2AttrList) {
				if (relationBean == null)
					continue;
				
				Integer masterID = (Integer) relationBean.getMasterID();
				Integer slaveID = (Integer) relationBean.getSlaveID();
				
				IdSetBean<Integer> idSet = metaCmpt2AttrMap.get(masterID);
				if (idSet == null) {
					idSet = new IdSetBean<Integer>();
					idSet.addId(slaveID);
					
					metaCmpt2AttrMap.put(masterID, idSet);
				} else {
					idSet.addId(slaveID);
				}
			}
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	private void LoadDeployFile() {
		try {
			intanceLock.lock();
			
			List<DeployFileBean> deployFileList = MetaDataService.loadDeployFile();
			if (deployFileList == null) {
				return;
			}
			
			deployFileMap.clear();
			
			for (DeployFileBean deployFile : deployFileList) {
				deployFileMap.put(deployFile.getFileType(), deployFile);
			}
			
		} catch (Exception e) {
			logger.error(e.getMessage(), e);
		} finally {
			intanceLock.unlock();
		}
	}
	
	public Map<String, ServiceBean> getServiceMap() {
		return serviceMap;
	}
	
	public String getServiceCollectdID(String servID) {
		ServiceBean servBean = serviceMap.get(servID);
		if (servBean == null)
			return null;
		
		String servType = servBean.getServType();
		String collectdCmptName = String.format("%s_COLLECTD", servType);
		Set<String> subs = topo.get(servID, CONSTS.TOPO_TYPE_CONTAIN);
		if (subs == null)
			return null;
		
		String collectdID = null;
		
		for (String subID : subs) {
			InstanceDtlBean instDtlBean = instanceDtlMap.get(subID);
			if (instDtlBean == null)
				continue;
			
			InstanceBean instBean = instDtlBean.getInstance();
			int cmptID = instBean.getCmptID();
			MetaComponentBean component = metaCmptMap.get(cmptID);
			if (component.getCmptName().equals(collectdCmptName)) {
				collectdID = instBean.getInstID();
				break;
			}
		}
		
		return collectdID;
	}
	
	public InstanceDtlBean getInstanceDtlBean(String instID) {
		if (instanceDtlMap == null)
			return null;
		
		return instanceDtlMap.get(instID);
	}
	
	public DeployFileBean getDeployFile(String type) {
		if (deployFileMap == null)
			return null;
		
		return deployFileMap.get(type);
	}
	
	public MetaComponentBean getComponentByName(String cmptName) {
		Integer cmptID = metaCmptName2IDMap.get(cmptName);
		if (cmptID == null)
			return null;
		
		return metaCmptMap.get(cmptID);
	}
	
	public MetaComponentBean getComponentByID(int cmptID) {
		return metaCmptMap.get(cmptID);
	}
	
	public List<MetaAttributeBean> getCmptAttrByName(String cmptName) {
		Integer cmptID = metaCmptName2IDMap.get(cmptName);
		if (cmptID == null)
			return null;
		
		IdSetBean<Integer> idSet = metaCmpt2AttrMap.get(cmptID);
		if (idSet == null)
			return null;
		
		List<MetaAttributeBean> attrs = new LinkedList<MetaAttributeBean>();
		Iterator<Integer> it = idSet.iterator();
		while (it.hasNext()) {
			Integer attrID = it.next();
			MetaAttributeBean attr = metaAttrMap.get(attrID);
			attrs.add(attr);
		}
		
		return attrs;
	}
	
	public List<MetaAttributeBean> getCmptAttrByID(Integer cmptID) {
		IdSetBean<Integer> idSet = metaCmpt2AttrMap.get(cmptID);
		if (idSet == null)
			return null;
		
		List<MetaAttributeBean> attrs = new LinkedList<MetaAttributeBean>();
		Iterator<Integer> it = idSet.iterator();
		while (it.hasNext()) {
			Integer attrID = it.next();
			MetaAttributeBean attr = metaAttrMap.get(attrID);
			attrs.add(attr);
		}
		
		return attrs;
	}
	
	public String getCmptIDAttrNameByID(Integer cmptID) {
		IdSetBean<Integer> idSet = metaCmpt2AttrMap.get(cmptID);
		if (idSet == null)
			return null;
		
		String idAttrName = null;
		Iterator<Integer> it = idSet.iterator();
		while (it.hasNext()) {
			Integer attrID = it.next();
			
			MetaAttributeBean attribute = metaAttrMap.get(attrID);
			if (attribute.getAttrName().indexOf(ID_INDEX) != -1) {
				idAttrName = attribute.getAttrName();
				break;
			}
		}
		
		return idAttrName;
	}
	
	public String getCmptIDAttrNameByName(String cmptName) {
		Integer cmptID = metaCmptName2IDMap.get(cmptName);
		if (cmptID == null)
			return null;
		
		return getCmptIDAttrNameByID(cmptID);
	}
	
	public Integer getComponentID(String cmptName) {
		return metaCmptName2IDMap.get(cmptName);
	}
	
	public IdSetBean<Integer> getAttrIdSet(Integer cmptID) {
		return metaCmpt2AttrMap.get(cmptID);
	}
	
	public MetaAttributeBean getAttributeByID(Integer attrID) {
		return metaAttrMap.get(attrID);
	}
	
	public Integer getQuotaCode(String quotaName) {
		if (collectQuotaMap == null)
			return null;
		
		return collectQuotaMap.get(quotaName);
	}
	
	public Topology getTopo() {
		return topo;
	}
	
}
