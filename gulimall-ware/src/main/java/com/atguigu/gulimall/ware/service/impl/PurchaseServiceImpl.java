package com.atguigu.gulimall.ware.service.impl;

import com.atguigu.common.constant.WareConstant;
import com.atguigu.gulimall.ware.entity.PurchaseDetailEntity;
import com.atguigu.gulimall.ware.service.PurchaseDetailService;
import com.atguigu.gulimall.ware.service.WareSkuService;
import com.atguigu.gulimall.ware.vo.MergeVo;
import com.atguigu.gulimall.ware.vo.PurchaseDoneVo;
import com.atguigu.gulimall.ware.vo.PurchaseItemDoneVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;

import com.atguigu.gulimall.ware.dao.PurchaseDao;
import com.atguigu.gulimall.ware.entity.PurchaseEntity;
import com.atguigu.gulimall.ware.service.PurchaseService;
import org.springframework.transaction.annotation.Transactional;


@Service("purchaseService")
public class PurchaseServiceImpl extends ServiceImpl<PurchaseDao, PurchaseEntity> implements PurchaseService {

    @Autowired
    PurchaseDetailService purchaseDetailService;

    @Autowired
    WareSkuService wareSkuService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(params),
                new QueryWrapper<PurchaseEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    public PageUtils queryPageUnreceivePurchase() {
        Map<String, Object> map = new HashMap<>();

        IPage<PurchaseEntity> page = this.page(
                new Query<PurchaseEntity>().getPage(map),
                new QueryWrapper<PurchaseEntity>().in("status", 0, 1)
        );

        return new PageUtils(page);
    }

    @Override
    @Transactional
    public void mergePurchase(MergeVo mergeVo) {
        Long purchaseId = mergeVo.getPurchaseId();
        if (purchaseId == null) {
            PurchaseEntity purchaseEntity = new PurchaseEntity();
            purchaseEntity.setCreateTime(new Date());
            purchaseEntity.setUpdateTime(new Date());
            purchaseEntity.setStatus(WareConstant.PurchaseStatusEnum.CREATED.getCode());
            this.save(purchaseEntity);
            purchaseId = purchaseEntity.getId();
        }

        // TODO 确认采购但是0，1才可以合单

        List<Long> items = mergeVo.getItems();
        Long finalPurchaseId = purchaseId;
        List<PurchaseDetailEntity> collect = items.stream().map(item -> {
            PurchaseDetailEntity purchaseDetailEntity = new PurchaseDetailEntity();
            purchaseDetailEntity.setId(item);
            purchaseDetailEntity.setPurchaseId(finalPurchaseId);
            purchaseDetailEntity.setStatus(WareConstant.PurchaseDetailStatusEnum.CREATED.getCode());
            return purchaseDetailEntity;
        }).collect(Collectors.toList());

        purchaseDetailService.updateBatchById(collect);

        PurchaseEntity purchaseEntity = new PurchaseEntity();
        purchaseEntity.setUpdateTime(new Date());
        purchaseEntity.setId(purchaseId);
        this.updateById(purchaseEntity);
    }

    @Override
    @Transactional
    public void received(List<Long> ids) {
        // 1.确认当前采购单是新建或者已分配状态
        List<PurchaseEntity> collect = ids.stream().map(id -> {
                    PurchaseEntity purchaseEntity = this.getById(id);
                    return purchaseEntity;
                }).filter(item -> item.getStatus() == WareConstant.PurchaseStatusEnum.CREATED.getCode()
                        || item.getStatus() == WareConstant.PurchaseStatusEnum.ASSIGNED.getCode())
                .map(item -> {
                    item.setStatus(WareConstant.PurchaseStatusEnum.RECEIVE.getCode());
                    item.setUpdateTime(new Date());
                    return item;
                }).collect(Collectors.toList());

        // 2.改变采购单的状态
        this.updateBatchById(collect);

        // 3.改变采购单所关联的需求的状态
        List<Long> list = collect.stream().map(item -> item.getId()).collect(Collectors.toList());
        List<PurchaseDetailEntity> ens = purchaseDetailService.list(new QueryWrapper<PurchaseDetailEntity>().in("purchase_id", list));
        for (PurchaseDetailEntity en : ens) {
            en.setStatus(WareConstant.PurchaseDetailStatusEnum.RECEIVE.getCode());
        }
        purchaseDetailService.updateBatchById(ens);

    }

    @Override
    @Transactional
    public void done(PurchaseDoneVo purchaseDoneVo) {
        // 1.改变采购单状态
        PurchaseEntity purchaseEntity = new PurchaseEntity();
        purchaseEntity.setId(purchaseDoneVo.getId());
        purchaseEntity.setUpdateTime(new Date());
        // 需要根据采购项的状态判断采购单的状态
//        this.updateById(purchaseEntity);

        // 2.改变采购项的状态
        boolean flag = true;
        List<PurchaseItemDoneVo> items = purchaseDoneVo.getItems();
        List<PurchaseDetailEntity> detailEntities = new ArrayList<>();
        // TODO reson未入库
        for (PurchaseItemDoneVo item : items) {
            PurchaseDetailEntity en = new PurchaseDetailEntity();
            en.setId(item.getItemId());
            if (item.getStatus() == WareConstant.PurchaseStatusEnum.HASERROR.getCode()) {
                flag = false;
                en.setStatus(WareConstant.PurchaseStatusEnum.HASERROR.getCode());
            } else {
                en.setStatus(WareConstant.PurchaseStatusEnum.FINISH.getCode());
                // 采购成功的入库
                PurchaseDetailEntity detailEntity = purchaseDetailService.getById(item.getItemId());
                wareSkuService.addStock(detailEntity.getSkuId(), detailEntity.getWareId(), detailEntity.getSkuNum());

            }
            detailEntities.add(en);
        }
        purchaseDetailService.updateBatchById(detailEntities);

        // 更新采购单状态
        if (flag) {
            purchaseEntity.setStatus(WareConstant.PurchaseStatusEnum.FINISH.getCode());
        } else {
            purchaseEntity.setStatus(WareConstant.PurchaseStatusEnum.HASERROR.getCode());
        }
        this.updateById(purchaseEntity);

        // 3.将成功采购的进行入库
    }

}