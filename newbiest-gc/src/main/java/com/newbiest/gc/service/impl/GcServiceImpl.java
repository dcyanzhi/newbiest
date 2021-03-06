package com.newbiest.gc.service.impl;

import com.google.common.collect.Lists;
import com.newbiest.base.exception.ClientException;
import com.newbiest.base.exception.ClientParameterException;
import com.newbiest.base.exception.ExceptionManager;
import com.newbiest.base.service.BaseService;
import com.newbiest.base.threadlocal.ThreadLocalContext;
import com.newbiest.ui.model.NBOwnerReferenceList;
import com.newbiest.ui.model.NBReferenceList;
import com.newbiest.base.ui.service.UIService;
import com.newbiest.base.utils.*;
import com.newbiest.common.exception.ContextException;
import com.newbiest.gc.GcExceptions;
import com.newbiest.gc.model.*;
import com.newbiest.gc.repository.*;
import com.newbiest.gc.service.GcService;
import com.newbiest.mms.dto.MaterialLotAction;
import com.newbiest.mms.model.*;
import com.newbiest.mms.repository.*;
import com.newbiest.mms.service.MmsService;
import com.newbiest.mms.state.model.MaterialEvent;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import sun.nio.cs.ext.MacArabic;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.newbiest.mms.exception.MmsException.MM_RAW_MATERIAL_IS_NOT_EXIST;

/**
 * Created by guoxunbo on 2019-08-21 12:41
 */
@Service
@Slf4j
@Transactional
public class GcServiceImpl implements GcService {

    public static final String TRANS_TYPE_BIND_RELAY_BOX = "BindRelayBox";
    public static final String TRANS_TYPE_UNBIND_RELAY_BOX = "UnbindRelayBox";
    public static final String TRANS_TYPE_JUDGE = "Judge";
    public static final String TRANS_TYPE_OQC = "OQC";

    public static final String REFERENCE_NAME_STOCK_OUT_CHECK_ITEM_LIST = "StockOutCheckItemList";
    public static final String REFERENCE_NAME_PACK_CASE_CHECK_ITEM_LIST = "PackCaseCheckItemList";

    public static final String EVENT_OQC = "OQC";

    public static final String WAREHOUSE_SH = "SH_STOCK";
    public static final String WAREHOUSE_ZJ = "ZJ_STOCK";

    @Autowired
    MesPackedLotRepository mesPackedLotRepository;

    @Autowired
    MaterialLotRepository materialLotRepository;

    @Autowired
    MaterialLotHistoryRepository materialLotHistoryRepository;

    @Autowired
    MmsService mmsService;

    @Autowired
    BaseService baseService;

    @Autowired
    UIService uiService;

    @Autowired
    WarehouseRepository warehouseRepository;

    @Autowired
    ErpSoRepository erpSoRepository;

    @Autowired
    ErpMaterialOutOrderRepository erpMaterialOutOrderRepository;

    @Autowired
    DeliveryOrderRepository deliveryOrderRepository;

    @Autowired
    ReTestOrderRepository reTestOrderRepository;

    @Autowired
    DocumentLineRepository documentLineRepository;

    @Autowired
    MaterialLotJudgeHisRepository materialLotJudgeHisRepository;

    @Autowired
    CheckHistoryRepository checkHistoryRepository;

    @Autowired
    MaterialLotInventoryRepository materialLotInventoryRepository;

    /**
     * 获取到可以入库的批次
     *  当前只验证了物料批次是否是完结
     * @param materialLotId
     * @return
     */
    public MaterialLot getWaitStockInStorageMaterialLot(String materialLotId) throws ClientException {
        try {
            MaterialLot materialLot = mmsService.getMLotByMLotId(materialLotId, true);
            materialLot.isFinish();
            return materialLot;
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 入库位
     * @return
     */
    public void stockIn(List<StockInModel> stockInModels) throws ClientException {
        try {
            Map<String, StockInModel> stockInModelMap = stockInModels.stream().collect(Collectors.toMap(StockInModel :: getMaterialLotId, Function.identity()));

            //1. 把箱批次和普通的物料批次区分出来
            List<MaterialLot> materialLots = stockInModels.stream().map(model -> mmsService.getMLotByMLotId(model.getMaterialLotId(), true)).collect(Collectors.toList());
            List<MaterialLot> packageMaterialLots = materialLots.stream().filter(materialLot -> !StringUtils.isNullOrEmpty(materialLot.getPackageType())).collect(Collectors.toList());
            List<MaterialLot> normalMaterialLots = materialLots.stream().filter(materialLot -> materialLot.getParentMaterialLotRrn() == null).collect(Collectors.toList());

            //2. 普通批次才做绑定中转箱功能，直接release原来的中转箱号
            for (MaterialLot materialLot : normalMaterialLots) {
                StockInModel stockInModel = stockInModelMap.get(materialLot.getMaterialLotId());
                // 为空则不处理
                if (StringUtils.isNullOrEmpty(stockInModel.getRelaxBoxId())) {
                    continue;
                }
                bindRelaxBox(Lists.newArrayList(materialLot), stockInModel.getRelaxBoxId());
            }
            //3. 入库
            for (MaterialLot materialLot : materialLots) {
                StockInModel stockInModel = stockInModelMap.get(materialLot.getMaterialLotId());
                String storageId = stockInModel.getStorageId();
                // 为空则不处理
                if (StringUtils.isNullOrEmpty(storageId)) {
                    continue;
                }
                if (StringUtils.isNullOrEmpty(materialLot.getReserved13())) {
                    throw new ClientParameterException(GcExceptions.MATERIAL_LOT_WAREHOUSE_IS_NULL, materialLot.getMaterialLotId());
                }

                MaterialLotAction action = new MaterialLotAction();
                action.setTargetWarehouseRrn(materialLot.getReserved13());
                action.setTargetStorageId(storageId);
                action.setTransQty(materialLot.getCurrentQty());

                List<MaterialLotInventory> materialLotInvList = mmsService.getMaterialLotInv(materialLot.getObjectRrn());
                // 如果为空就是做入库事件 如果不是空则做转库事件
                if (CollectionUtils.isNotEmpty(materialLotInvList)) {
                    //GC一个批次只会入库一次
                    MaterialLotInventory materialLotInventory = materialLotInvList.get(0);
                    action.setFromWarehouseRrn(materialLotInventory.getWarehouseRrn());
                    action.setFromStorageRrn(materialLotInventory.getStorageRrn());
                    mmsService.transfer(materialLot, action);
                } else {
                    materialLot = mmsService.stockIn(materialLot, action);
                }
                materialLot.setReserved14(storageId);
                materialLotRepository.save(materialLot);
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * GC盘点。
     * 这个盘点不是传统上的盘点库存数量，而是只记录盘点历史。会传递不存在的
     * 应格科要求要记录到一个单独表中。记录这个盘点历史。
     *
     */
    public void checkMaterialInventory(List<MaterialLot> existMaterialLots, List<MaterialLot> notExistMaterialLots) throws ClientException {
        try {
            if (CollectionUtils.isNotEmpty(existMaterialLots)) {
                for (MaterialLot materialLot : existMaterialLots) {
                    materialLot = mmsService.getMLotByMLotId(materialLot.getMaterialLotId(), true);
                    MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, MaterialLotHistory.TRANS_TYPE_CHECK);
                    history.setTransQty(materialLot.getCurrentQty());
                    materialLotHistoryRepository.save(history);

                    CheckHistory checkHistory = new CheckHistory();
                    PropertyUtils.copyProperties(materialLot, checkHistory, new HistoryBeanConverter());
                    checkHistory.setTransQty(materialLot.getCurrentQty());
                    checkHistory.setTransType(MaterialLotHistory.TRANS_TYPE_CHECK);
                    checkHistory.setObjectRrn(null);
                    checkHistory.setHisSeq(ThreadLocalContext.getTransactionId());
                    checkHistoryRepository.save(checkHistory);
                }
            }

            if (CollectionUtils.isNotEmpty(notExistMaterialLots)) {
                for (MaterialLot materialLot : notExistMaterialLots) {
                    CheckHistory checkHistory = new CheckHistory();
                    PropertyUtils.copyProperties(materialLot, checkHistory, new HistoryBeanConverter());
                    checkHistory.setTransQty(BigDecimal.ZERO);
                    checkHistory.setErrorFlag(true);
                    checkHistory.setActionCode("Error");
                    checkHistory.setTransType(MaterialLotHistory.TRANS_TYPE_CHECK);
                    checkHistory.setObjectRrn(null);
                    checkHistory.setHisSeq(ThreadLocalContext.getTransactionId());
                    checkHistoryRepository.save(checkHistory);
                }
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 物料批次根据重测单进行重测，更新单据数据以及，更改ERP的中间表数据
     *  documentLine 产品型号 materialName，二级代码 reserved2，等级 reserved3 一致
     *  materialLot 产品型号 materialName，二级代码 reserved1，等级grade 一致
     */
    public void reTest(DocumentLine documentLine, List<MaterialLotAction> materialLotActions) throws ClientException{
        try {
            List<MaterialLot> materialLots = materialLotActions.stream().map(materialLotAction -> mmsService.getMLotByMLotId(materialLotAction.getMaterialLotId(), true)).collect(Collectors.toList());
            for (MaterialLot materialLot : materialLots) {
                validationDocLine(documentLine, materialLot);
            }
            BigDecimal handledQty = BigDecimal.ZERO;

            for (MaterialLot materialLot : materialLots) {
                handledQty = handledQty.add(materialLot.getCurrentQty());

                // 变更事件，并清理掉库存
                materialLot.setCurrentQty(BigDecimal.ZERO);

                materialLot.setReserved12(documentLine.getObjectRrn().toString());
                mmsService.changeMaterialLotState(materialLot, "ReTest", StringUtils.EMPTY);
                MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, "ReTest");
                materialLotHistoryRepository.save(history);

                materialLotInventoryRepository.deleteByMaterialLotRrn(materialLot.getObjectRrn());
            }

            // 验证当前操作数量是否超过待检查数量
            BigDecimal unHandleQty =  documentLine.getUnHandledQty().subtract(handledQty);
            if (unHandleQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new ClientParameterException(GcExceptions.OVER_DOC_QTY);
            }
            documentLine = documentLineRepository.findByObjectRrn(documentLine.getObjectRrn());
            documentLine.setHandledQty(documentLine.getHandledQty().add(handledQty));
            documentLine.setUnHandledQty(unHandleQty);
            documentLineRepository.save(documentLine);

            // 获取到主单据
            ReTestOrder reTestOrder = reTestOrderRepository.findByObjectRrn(documentLine.getDocRrn());
            reTestOrder.setHandledQty(reTestOrder.getHandledQty().add(handledQty));
            reTestOrder.setUnHandledQty(reTestOrder.getUnHandledQty().subtract(handledQty));
            reTestOrderRepository.save(reTestOrder);

            Optional<ErpMaterialOutOrder> erpMaterialOutOrderOptional = erpMaterialOutOrderRepository.findById(Long.valueOf(documentLine.getReserved1()));
            if (!erpMaterialOutOrderOptional.isPresent()) {
                throw new ClientParameterException(GcExceptions.ERP_RETEST_ORDER_IS_NOT_EXIST, documentLine.getReserved1());
            }

            ErpMaterialOutOrder erpMaterialOutOrder = erpMaterialOutOrderOptional.get();
            erpMaterialOutOrder.setSynStatus(ErpMaterialOutOrder.SYNC_STATUS_OPERATION);
            erpMaterialOutOrder.setLeftNum(erpMaterialOutOrder.getLeftNum().subtract(handledQty));
            if (StringUtils.isNullOrEmpty(erpMaterialOutOrder.getDeliveredNum())) {
                erpMaterialOutOrder.setDeliveredNum(handledQty.toPlainString());
            } else {
                BigDecimal docHandledQty = new BigDecimal(erpMaterialOutOrder.getDeliveredNum());
                docHandledQty = docHandledQty.add(handledQty);
                erpMaterialOutOrder.setDeliveredNum(docHandledQty.toPlainString());
            }
            erpMaterialOutOrderRepository.save(erpMaterialOutOrder);
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    public void validationDocLine(DocumentLine documentLine, MaterialLot materialLot) throws ClientException{
        try {
            Assert.assertEquals(documentLine.getMaterialName(), materialLot.getMaterialName());
        } catch (AssertionError e) {
            throw new ClientParameterException(ContextException.MERGE_SOURCE_VALUE_IS_NOT_SAME_TARGET_VALUE, "materialName", documentLine.getMaterialName(), materialLot.getMaterialName());
        }

        String materialSecondCode = materialLot.getReserved1() + materialLot.getGrade();
        try {
            Assert.assertEquals(documentLine.getReserved2(), materialSecondCode);
        } catch (AssertionError e) {
            throw new ClientParameterException(ContextException.MERGE_SOURCE_VALUE_IS_NOT_SAME_TARGET_VALUE, "secondcode", documentLine.getReserved2(),  materialSecondCode);
        }

        try {
            Assert.assertEquals(documentLine.getReserved3(), materialLot.getGrade());
        } catch (AssertionError e) {
            throw new ClientParameterException(ContextException.MERGE_SOURCE_VALUE_IS_NOT_SAME_TARGET_VALUE, "grade", documentLine.getReserved3(), materialLot.getGrade());
        }
        try {
            Assert.assertEquals(documentLine.getReserved7(), materialLot.getReserved6());
        } catch (AssertionError e) {
            throw new ClientParameterException(ContextException.MERGE_SOURCE_VALUE_IS_NOT_SAME_TARGET_VALUE, "other1", documentLine.getReserved7(), materialLot.getReserved6());
        }
    }

    /**
     * 物料批次根据发货单进行发货，更新单据数据以及，更改ERP的中间表数据
     *  documentLine 产品型号 materialName，二级代码 reserved2，等级 reserved3,  物流 reserved7 一致
     *  materialLot 产品型号 materialName，二级代码 reserved1，等级 grade,  物料 reserved6 一致
     */
    public void stockOut(DocumentLine documentLine, List<MaterialLotAction> materialLotActions) throws ClientException{
        try {
            documentLine = documentLineRepository.findByObjectRrn(documentLine.getObjectRrn());
            List<MaterialLot> materialLots = materialLotActions.stream().map(materialLotAction -> mmsService.getMLotByMLotId(materialLotAction.getMaterialLotId(), true)).collect(Collectors.toList());
            for (MaterialLot materialLot : materialLots) {
                validationDocLine(documentLine, materialLot);
            }

            BigDecimal handledQty = BigDecimal.ZERO;
            for (MaterialLot materialLot : materialLots) {
                handledQty = handledQty.add(materialLot.getCurrentQty());
                // 变更事件，并清理掉库存
                materialLot.setCurrentQty(BigDecimal.ZERO);
                materialLot.setReserved12(documentLine.getObjectRrn().toString());
                mmsService.changeMaterialLotState(materialLot,  MaterialEvent.EVENT_SHIP, StringUtils.EMPTY);
                materialLotInventoryRepository.deleteByMaterialLotRrn(materialLot.getObjectRrn());
                MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, MaterialLotHistory.TRANS_TYPE_SHIP);
                materialLotHistoryRepository.save(history);
            }

            // 验证当前操作数量是否超过待检查数量
            BigDecimal unHandleQty =  documentLine.getUnHandledQty().subtract(handledQty);
            if (unHandleQty.compareTo(BigDecimal.ZERO) < 0) {
                throw new ClientParameterException(GcExceptions.OVER_DOC_QTY);
            }

            documentLine = documentLineRepository.findByObjectRrn(documentLine.getObjectRrn());
            documentLine.setHandledQty(documentLine.getHandledQty().add(handledQty));
            documentLine.setUnHandledQty(unHandleQty);
            documentLineRepository.save(documentLine);

            // 获取到主单据
            DeliveryOrder deliveryOrder = deliveryOrderRepository.findByObjectRrn(documentLine.getDocRrn());
            deliveryOrder.setHandledQty(deliveryOrder.getHandledQty().add(handledQty));
            deliveryOrder.setUnHandledQty(deliveryOrder.getUnHandledQty().subtract(handledQty));
            deliveryOrderRepository.save(deliveryOrder);

            Optional<ErpSo> erpSoOptional = erpSoRepository.findById(Long.valueOf(documentLine.getReserved1()));
            if (!erpSoOptional.isPresent()) {
                throw new ClientParameterException(GcExceptions.ERP_SO_IS_NOT_EXIST, documentLine.getReserved1());
            }

            ErpSo erpSo = erpSoOptional.get();
            erpSo.setSynStatus(ErpMaterialOutOrder.SYNC_STATUS_OPERATION);
            erpSo.setLeftNum(erpSo.getLeftNum().subtract(handledQty));
            if (StringUtils.isNullOrEmpty(erpSo.getDeliveredNum())) {
                erpSo.setDeliveredNum(handledQty.toPlainString());
            } else {
                BigDecimal docHandledQty = new BigDecimal(erpSo.getDeliveredNum());
                docHandledQty = docHandledQty.add(handledQty);
                erpSo.setDeliveredNum(docHandledQty.toPlainString());
            }
            erpSoRepository.save(erpSo);

        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }


    /**
     * 出货前检查。
     *  直接以检查结果做状态 当stockOutCheckList为空的时候就是OK。因为GC要求，OK时候不记录检查项
     * @param materialLots
     * @param stockOutCheckList 检查项
     * @return
     */
    public void stockOutCheck(List<MaterialLot> materialLots, List<StockOutCheck> stockOutCheckList) throws ClientException {
        try {
            String checkResult = StockOutCheck.RESULT_OK;
            List<StockOutCheck> ngStockOutCheckList = Lists.newArrayList();
            if (CollectionUtils.isNotEmpty(stockOutCheckList)) {
                ngStockOutCheckList = stockOutCheckList.stream().filter(checkItem -> StockOutCheck.RESULT_NG.equals(checkItem.getResult())).collect(Collectors.toList());
            }
            if (CollectionUtils.isNotEmpty(ngStockOutCheckList)) {
                checkResult = StockOutCheck.RESULT_NG;

            }
            for (MaterialLot materialLot : materialLots) {
                //20190917 GC要求 如果判了NG。并且装箱检查是PASS的，将PASS改成PASS0
                if (StockOutCheck.RESULT_NG.equals(checkResult) && StockOutCheck.RESULT_PASS.equals(materialLot.getReserved9())) {
                    materialLot.setReserved9(materialLot.getReserved9() + "0");

                    // 20190921 GC要求，被包装的批次都需要也需要改成materialLot相关信息
                    List<MaterialLot> packedMaterialLots = materialLotRepository.getPackageDetailLots(materialLot.getObjectRrn());
                    if (CollectionUtils.isNotEmpty(packedMaterialLots)) {
                        for (MaterialLot packedMaterialLot : packedMaterialLots) {
                            packedMaterialLot.setReserved9(materialLot.getReserved9());
                            materialLotRepository.save(packedMaterialLot);
                        }
                    }
                }
                materialLot = mmsService.changeMaterialLotState(materialLot, EVENT_OQC, checkResult);
//              GC要求只记录NG的判定历史即可
                if (CollectionUtils.isNotEmpty(ngStockOutCheckList)) {
                    // 保存每个项目的判定结果
                    MaterialLot finalMaterialLot = materialLot;
                    ngStockOutCheckList.forEach(stockOutCheck -> {
                        MaterialLotJudgeHis materialLotJudgeHis = new MaterialLotJudgeHis();
                        materialLotJudgeHis.setMaterialLotRrn(finalMaterialLot.getObjectRrn());
                        materialLotJudgeHis.setMaterialLotId(finalMaterialLot.getMaterialLotId());
                        materialLotJudgeHis.setItemName(stockOutCheck.getName());
                        materialLotJudgeHis.setResult(stockOutCheck.getResult());
                        materialLotJudgeHis.setTransType(MaterialLotJudgeHis.TRANS_TYPE_OQC);
                        materialLotJudgeHis.setHisSeq(ThreadLocalContext.getTransactionId());
                        materialLotJudgeHisRepository.save(materialLotJudgeHis);
                    });
                }
                MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, TRANS_TYPE_OQC);
                materialLotHistoryRepository.save(history);
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 获取装箱检验的检查项
     * @return
     * @throws ClientException
     */
    public List<NBOwnerReferenceList> getJudgePackCaseCheckList() throws ClientException {
        List<NBOwnerReferenceList> nbReferenceList = (List<NBOwnerReferenceList>) uiService.getReferenceList(REFERENCE_NAME_PACK_CASE_CHECK_ITEM_LIST, NBReferenceList.CATEGORY_OWNER);
        if (CollectionUtils.isNotEmpty(nbReferenceList)) {
            return nbReferenceList;
        }
        return Lists.newArrayList();
    }

    public List<NBOwnerReferenceList> getStockOutCheckList() throws ClientException {
        List<NBOwnerReferenceList> nbReferenceList = (List<NBOwnerReferenceList>) uiService.getReferenceList(REFERENCE_NAME_STOCK_OUT_CHECK_ITEM_LIST, NBReferenceList.CATEGORY_OWNER);
        if (CollectionUtils.isNotEmpty(nbReferenceList)) {
            return nbReferenceList;
        }
        return Lists.newArrayList();
    }

    /**
     * 接收MES的完成品
     * @param packedLotList
     */
    public void receiveFinishGood(List<MesPackedLot> packedLotList) throws ClientException {
        try {
            Map<String, List<MesPackedLot>> packedLotMap = packedLotList.stream().map(packedLot -> mesPackedLotRepository.findByBoxId(packedLot.getBoxId())).collect(Collectors.groupingBy(MesPackedLot :: getProductId));
            packedLotMap.keySet().forEach(productId -> {
                RawMaterial rawMaterial = mmsService.getRawMaterialByName(productId);
                if (rawMaterial == null) {
                    throw new ClientParameterException(MM_RAW_MATERIAL_IS_NOT_EXIST, productId);
                }

                List<MesPackedLot> mesPackedLots = packedLotMap.get(productId);
                for (MesPackedLot mesPackedLot : mesPackedLots) {
                    MaterialLotAction materialLotAction = new MaterialLotAction();
                    materialLotAction.setGrade(mesPackedLot.getGrade());
                    materialLotAction.setTransQty(BigDecimal.valueOf(mesPackedLot.getQuantity()));

                    // 工单前2位是SH的入SH仓库，是ZJ的入浙江仓库
                    String warehouseName = WAREHOUSE_SH;
                    String location = mesPackedLot.getWorkorderId().substring(0, 2);
                    if (location.equalsIgnoreCase("ZJ")) {
                        warehouseName = WAREHOUSE_ZJ;
                    }
                    Warehouse warehouse = mmsService.getWarehouseByName(warehouseName);
                    if (warehouse == null) {
                        warehouse = new Warehouse();
                        warehouse.setName(warehouseName);
                        warehouse = warehouseRepository.saveAndFlush(warehouse);
                    }
                    materialLotAction.setTargetWarehouseRrn(warehouse.getObjectRrn());
                    MaterialLot materialLot = mmsService.receiveMLot2Warehouse(rawMaterial, mesPackedLot.getBoxId(), materialLotAction);

                    materialLot.setWorkOrderId(mesPackedLot.getWorkorderId());
                    // 预留栏位赋值
                    materialLot.setReserved1(mesPackedLot.getLevelTwoCode());
                    materialLot.setReserved2(mesPackedLot.getWaferId());
                    materialLot.setReserved3(mesPackedLot.getSalesNote());
                    materialLot.setReserved4(mesPackedLot.getTreasuryNote());
                    materialLot.setReserved5(mesPackedLot.getProductionNote());
                    materialLot.setReserved6(mesPackedLot.getBondedProperty());
                    materialLot.setReserved7(mesPackedLot.getProductCategory());
                    materialLot.setReserved13(warehouse.getObjectRrn().toString());
                    materialLotRepository.save(materialLot);

                    // 修改MES成品批次为接收状态
                    mesPackedLot.setPackedStatus(MesPackedLot.PACKED_STATUS_RECEIVED);
                    mesPackedLotRepository.save(mesPackedLot);
                }
            });

        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }
    }

    /**
     * 物料批次绑定中转箱
     * @throws ClientException
     */
    public void bindRelaxBox(List<MaterialLot> materialLots, String relaxBoxId) throws ClientException{
        try {
            materialLots.forEach(materialLot -> {
                materialLot.setReserved8(relaxBoxId);
                materialLotRepository.save(materialLot);

                MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, TRANS_TYPE_BIND_RELAY_BOX);
                materialLotHistoryRepository.save(history);
            });
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }

    }

    /**
     * 物料批次取消绑定中转箱
     * @throws ClientException
     */
    public void unbindRelaxBox(List<MaterialLot> materialLots) throws ClientException{
        try {
            materialLots.forEach(materialLot -> {
                materialLot.setReserved8(StringUtils.EMPTY);
                materialLotRepository.save(materialLot);

                MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, TRANS_TYPE_UNBIND_RELAY_BOX);
                materialLotHistoryRepository.save(history);
            });
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }

    }

    public void judgeMaterialLot(MaterialLot materialLot, String judgeGrade, String judgeCode, List<StockOutCheck> ngCheckList) {
        materialLot.setReserved9(judgeGrade);
        materialLot.setReserved10(judgeCode);
        materialLotRepository.save(materialLot);
        MaterialLotHistory history = (MaterialLotHistory) baseService.buildHistoryBean(materialLot, TRANS_TYPE_JUDGE);
        materialLotHistoryRepository.save(history);

        if (CollectionUtils.isNotEmpty(ngCheckList)) {
            // 保存每个项目的判定结果 GC要求只记录NG的判定历史即可
            MaterialLot finalMaterialLot = materialLot;
            ngCheckList.forEach(stockOutCheck -> {
                MaterialLotJudgeHis materialLotJudgeHis = new MaterialLotJudgeHis();
                materialLotJudgeHis.setMaterialLotRrn(finalMaterialLot.getObjectRrn());
                materialLotJudgeHis.setMaterialLotId(finalMaterialLot.getMaterialLotId());
                materialLotJudgeHis.setItemName(stockOutCheck.getName());
                materialLotJudgeHis.setResult(stockOutCheck.getResult());
                materialLotJudgeHis.setTransType(TRANS_TYPE_JUDGE);
                materialLotJudgeHis.setHisSeq(ThreadLocalContext.getTransactionId());
                materialLotJudgeHisRepository.save(materialLotJudgeHis);
            });
        }
    }

    /**
     * 装箱判定
     * @throws ClientException
     */
    public void judgePackedMaterialLot(List<MaterialLot> materialLots,List<StockOutCheck> checkList) throws ClientException{
        try {
            String judgeGrade = StockOutCheck.RESULT_PASS;
            String judgeCode = StockOutCheck.RESULT_PASS;
            List<StockOutCheck> ngCheckList = Lists.newArrayList();
            if (CollectionUtils.isNotEmpty(checkList)) {
                ngCheckList = checkList.stream().filter(checkItem -> StockOutCheck.RESULT_NG.equals(checkItem.getResult())).collect(Collectors.toList());
            }
            if (CollectionUtils.isNotEmpty(ngCheckList)) {
                judgeGrade = StockOutCheck.RESULT_NG;
                judgeCode = StockOutCheck.RESULT_NG;
            }

            //GC只会全部包装。故此处，直接用ParentMaterialLotId做包装号。
            Map<String, List<MaterialLot>> packedLotMap = materialLots.stream().map(materialLotAction -> mmsService.getMLotByMLotId(materialLotAction.getMaterialLotId(), true))
                    .collect(Collectors.groupingBy(MaterialLot::getParentMaterialLotId));
            for (String packageMLotId : packedLotMap.keySet())  {
                MaterialLot parentMLot = mmsService.getMLotByMLotId(packageMLotId, true);
                judgeMaterialLot(parentMLot, judgeGrade, judgeCode, ngCheckList);

                for (MaterialLot packagedMLot : packedLotMap.get(packageMLotId)) {
                    judgeMaterialLot(packagedMLot, judgeGrade, judgeCode, ngCheckList);
                }
            }
        } catch (Exception e) {
            throw ExceptionManager.handleException(e, log);
        }

    }

}
