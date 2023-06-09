package com.atguigu.gulimall.product.service.impl;

import com.alibaba.fastjson.TypeReference;
import com.atguigu.common.constant.ProductConstant;
import com.atguigu.common.to.SkuHasStockTo;
import com.atguigu.common.to.SkuReductionTo;
import com.atguigu.common.to.SpuBoundsTo;
import com.atguigu.common.to.es.SkuEsModel;
import com.atguigu.common.utils.R;
import com.atguigu.gulimall.product.entity.*;
import com.atguigu.gulimall.product.feign.CouponFeignService;
import com.atguigu.gulimall.product.feign.SearchFeignService;
import com.atguigu.gulimall.product.feign.WareFeignService;
import com.atguigu.gulimall.product.service.*;
import com.atguigu.gulimall.product.vo.*;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.atguigu.common.utils.PageUtils;
import com.atguigu.common.utils.Query;

import com.atguigu.gulimall.product.dao.SpuInfoDao;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;


@Service("spuInfoService")
public class SpuInfoServiceImpl extends ServiceImpl<SpuInfoDao, SpuInfoEntity> implements SpuInfoService {

    @Autowired
    SpuInfoDescService spuInfoDescService;

    @Autowired
    SpuImagesService spuImagesService;

    @Autowired
    ProductAttrValueService productAttrValueService;

    @Autowired
    SkuInfoService skuInfoService;

    @Autowired
    SkuImagesService skuImagesService;

    @Autowired
    SkuSaleAttrValueService skuSaleAttrValueService;

    @Autowired
    CouponFeignService couponFeignService;

    @Autowired
    BrandService brandService;

    @Autowired
    CategoryService categoryService;

    @Autowired
    AttrService attrService;

    @Autowired
    WareFeignService wareFeignService;

    @Autowired
    SearchFeignService searchFeignService;

    @Override
    public PageUtils queryPage(Map<String, Object> params) {
        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                new QueryWrapper<SpuInfoEntity>()
        );

        return new PageUtils(page);
    }

    @Override
    @Transactional
    public void saveSpuInfo(SpuSaveVo vo) {
        // 1.保存spu基本信息 pms_spu_info
        SpuInfoEntity spuInfoEntity = new SpuInfoEntity();
        BeanUtils.copyProperties(vo, spuInfoEntity);
        spuInfoEntity.setCreateTime(new Date());
        spuInfoEntity.setUpdateTime(new Date());
        this.save(spuInfoEntity);

        // 2.保存spu的描述 pms_spu_info_desc
        SpuInfoDescEntity spuInfoDescEntity = new SpuInfoDescEntity();
        spuInfoDescEntity.setSpuId(spuInfoEntity.getId());
        spuInfoDescEntity.setDecript(String.join(",", vo.getDecript()));
        spuInfoDescService.saveSpuInfoDesc(spuInfoDescEntity);

        // 3.保存spu的图片集 pms_spu_images
        List<String> images = vo.getImages();
        spuImagesService.saveImages(spuInfoEntity.getId(), images);

        // 4.保存spu的规格参数 pms_product_attr_value
        List<BaseAttrs> baseAttrs = vo.getBaseAttrs();
        productAttrValueService.saveBaseAttrs(spuInfoEntity.getId(), baseAttrs);

        // 5.保存spu的积分信息 gulimall_sms -> sms_spu_bounds
        Bounds bounds = vo.getBounds();
        SpuBoundsTo spuBoundsTo = new SpuBoundsTo();
        spuBoundsTo.setBuyBounds(bounds.getBuyBounds());
        spuBoundsTo.setGrowBounds(bounds.getGrowBounds());
        spuBoundsTo.setSpuId(spuInfoEntity.getId());
        R r = couponFeignService.saveSpuBounds(spuBoundsTo);
        if (r.getCode() != 0) {
            log.error("远程保存spu积分信息失败");
        }

        // 6.保存当前spu对应的sku信息
        saveSkuInfo(vo, spuInfoEntity);
    }

    @Override
    public PageUtils queryPageByCondition(Map<String, Object> params) {
        QueryWrapper<SpuInfoEntity> wrapper = new QueryWrapper<>();
        String key = (String) params.get("key");
        //{
        //   page: 1,//当前页码
        //   limit: 10,//每页记录数
        //   sidx: 'id',//排序字段
        //   order: 'asc/desc',//排序方式
        //   key: '华为',//检索关键字
        //   catelogId: 6,//三级分类id
        //   brandId: 1,//品牌id
        //   status: 0,//商品状态
        //}
        if (!StringUtils.isEmpty(key)) {
            wrapper.and((w) -> {
                w.eq("id", key).or().like("spu_name", key);
            });
        }

        String status = (String) params.get("status");
        if (!StringUtils.isEmpty(status)) {
            wrapper.and(w -> w.eq("publish_status", status));
        }

        String brandId = (String) params.get("brandId");
        if (!StringUtils.isEmpty(brandId) && !"0".equals(brandId)) {
            wrapper.and(w -> w.eq("brand_id", brandId));
        }

        // TODO
        // catelog ？ catalog
        String catalogId = (String) params.get("catalogId");
        if (!StringUtils.isEmpty(catalogId) && !"0".equals(catalogId)) {
            wrapper.and(w -> w.eq("catalog_id", catalogId));
        }

        IPage<SpuInfoEntity> page = this.page(
                new Query<SpuInfoEntity>().getPage(params),
                wrapper
        );

        return new PageUtils(page);
    }

    @Override
    public void up(Long spuId) {
        //1.查出当前spuId对应的所有sku信息，品牌的名字
        List<SkuInfoEntity> skus = skuInfoService.getSkusBySpuId(spuId);
        List<Long> skuIdList = skus.stream().map(SkuInfoEntity::getSkuId).collect(Collectors.toList());

        // TODO 4.查询当前sku的所有可以被用来进行检索规格属性
        List<ProductAttrValueEntity> productAttrValueEntities = productAttrValueService.baseAttrListForSpu(spuId);
        List<Long> attrIds = productAttrValueEntities.stream().map(attr -> {
            return attr.getAttrId();
        }).collect(Collectors.toList());
        List<Long> searchIds = attrService.selectSearchAttrIds(attrIds);
        Set<Long> searchIdsSet = new HashSet<>(searchIds);
        List<SkuEsModel.Attrs> attrsSearch = productAttrValueEntities.stream().filter(en -> searchIdsSet.contains(en.getAttrId())).map(en -> {
            SkuEsModel.Attrs attrs = new SkuEsModel.Attrs();
            BeanUtils.copyProperties(en, attrs);
            return attrs;
        }).collect(Collectors.toList());


        // TODO 1.发送远程调用，库存系统是否有库存
        Map<Long, Boolean> stockMap = null;
        try {
            R skusHasStock = wareFeignService.getSkusHasStock(skuIdList);
            // TODO getData为null
            List<SkuHasStockTo> data = skusHasStock.getData(new TypeReference<List<SkuHasStockTo>>(){});
            stockMap = data.stream().collect(Collectors.toMap(SkuHasStockTo::getSkuId, item -> item.getHasStock()));
        } catch (Exception e) {
            log.error("库存服务查询异常：原因{}", e);
        }

        //2.封装每个sku的信息
        Map<Long, Boolean> finalStockMap = stockMap;
        List<SkuEsModel> upProductList = skus.stream().map(sku -> {
            SkuEsModel esModel = new SkuEsModel();
            BeanUtils.copyProperties(sku, esModel);
            // skuPrice, skuImg, hasStock, hotScore, brandName, brandImg, catalogName, attrs
            esModel.setSkuPrice(sku.getPrice());
            esModel.setSkuImg(sku.getSkuDefaultImg());
            esModel.setHasStock(finalStockMap == null ? true : finalStockMap.get(sku.getSkuId()));

            // TODO 2.热度评分
            esModel.setHotScore(0L);

            // TODO 3.查询品牌和分类的名字信息
            BrandEntity brandEntity = brandService.getById(sku.getBrandId());
            esModel.setBrandName(brandEntity.getName());
            esModel.setBrandImg(brandEntity.getLogo());
            CategoryEntity categoryEntity = categoryService.getById(esModel.getCatalogId());
            esModel.setCatalogName(categoryEntity.getName());
            esModel.setAttrs(attrsSearch);
            return esModel;
        }).collect(Collectors.toList());

        //TODO 5.将输入发送给es进行保存
        R r = searchFeignService.productStatusUp(upProductList);
        if (r.getCode() == 0) {
            // 成功
            // TODO 6.修改spu状态
            this.baseMapper.updateSpuStatus(spuId, ProductConstant.StatusEnum.SPU_UP.getCode());
        } else {
            // 接口调用失败
            // TODO 7.重复调用 接口幂等性 重试机制 ？
            // Feign调用流程
            // 1.构造请求数据，将对象转为json
            // 2.发送请求进行执行（执行成功会解码响应数据）
            // 3.执行请求会有重试机制
        }
    }

    private void saveSkuInfo(SpuSaveVo vo, SpuInfoEntity spuInfoEntity) {
        // 6.保存当前spu对应的所有sku信息
        // 6.1 sku的基本信息 pms_sku_info
        List<Skus> skus = vo.getSkus();
        skus.stream().forEach(sku -> {
            String defaultImg = "";
            for (Images image : sku.getImages()) {
                if (image.getDefaultImg() == 1) {
                    defaultImg = image.getImgUrl();
                    break;
                }
            }
            SkuInfoEntity skuInfoEntity = new SkuInfoEntity();
            BeanUtils.copyProperties(sku, skuInfoEntity);
            skuInfoEntity.setBrandId(spuInfoEntity.getBrandId());
            skuInfoEntity.setCatalogId(spuInfoEntity.getCatalogId());
            skuInfoEntity.setSpuId(spuInfoEntity.getId());
            skuInfoEntity.setSaleCount(0L);
            skuInfoEntity.setSkuDefaultImg(defaultImg);
            skuInfoService.saveSkuInfo(skuInfoEntity);

            // 6.2 sku的图片信息 pms_sku_images
            Long skuId = skuInfoEntity.getSkuId();
            skuImagesService.saveImages(skuId, sku.getImages());

            // 6.3 sku的销售属性值 pms_sku_sale_attr_value
            skuSaleAttrValueService.saveAttrs(skuId, sku.getAttr());

            // 6.4 sku的优惠、满减信息 gulimal_sms -> sms_sku_ladder, sms_sku_full_reduction, sms_member_price
            SkuReductionTo skuReductionTo = new SkuReductionTo();
            BeanUtils.copyProperties(sku, skuReductionTo);
            skuReductionTo.setSkuId(skuId);
            if (skuReductionTo.getFullCount() <= 0 && skuReductionTo.getFullPrice().compareTo(new BigDecimal("0")) != 1) {
                return;
            }
            R r1 = couponFeignService.saveSkuReduction(skuReductionTo);
            if (r1.getCode() != 0) {
                log.error("远程保存sku信息失败");
            }
        });
    }

}