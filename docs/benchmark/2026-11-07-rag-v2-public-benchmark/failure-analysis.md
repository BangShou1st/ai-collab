# 失败与负收益案例分析（RAG_V2_PUBLIC_BENCHMARK-01）

## Benchmark A：DuRetrieval

- 被全部六个变体同时 Top10 命中的题：104/300（其余 196 题至少被一个变体漏检，主要由 Lexical 弱召回贡献）
- 其中 Dense Only（A1）Top10 未命中的题：11
  - 08ed5dd3b40b6043b2737cd81b8ccb07: relevantCount=1 latencyMs=446
  - 1160b7ff03cf7cfff2a9548679483fd7: relevantCount=1 latencyMs=406
  - 39eb1062625fe0197bd3536951497916: relevantCount=1 latencyMs=464
  - 45b4bac4d15c103f6370210aa77c57e1: relevantCount=1 latencyMs=542
  - 6e79ae50291aec984f3db675cf905719: relevantCount=15 latencyMs=465
  - 7753b5ecd5d762cf571a220dc984e36c: relevantCount=1 latencyMs=445
  - 7a6f7e5ff945dbcbe6b127d9d31c3f2b: relevantCount=1 latencyMs=436
  - ac9b4dbf9625e4ed960408d12c3bcbf2: relevantCount=5 latencyMs=445
  - d3500c71ea08043d3bcf23ffced5e9b1: relevantCount=1 latencyMs=430
  - f4161b71a41d3477b36318bb1bb1cdeb: relevantCount=1 latencyMs=486
  - fafeac27983787443e800546ad0763ac: relevantCount=1 latencyMs=372

### RRF 相对 Dense 退步（A3_DENSE_LEXICAL_RRF vs A1_DENSE_ONLY）

- 退步题数：234

| qid | nDCG Δ | a nDCG | b nDCG |
|---|---|---|---|
| f0a2fcc262aa5ef47d874c845b07c4e0 | -0.694 | 0.239 | 0.934 |
| a96e79a5ed1fe7e028a2681c9c906cc9 | -0.621 | 0.371 | 0.992 |
| 9804222f036c08e014aa5e06734474a7 | -0.592 | 0.369 | 0.961 |
| 536d5c2df162a12d9d8be0ee05128ed0 | -0.581 | 0.356 | 0.937 |
| 7740d5ff5181921405faffe173321ad9 | -0.569 | 0.431 | 1.000 |
| eddc59bb0cafa0d6aa47c9cd79ab118d | -0.533 | 0.467 | 1.000 |
| 4a2f9daf682a5ec0ae4f933e67e39c09 | -0.527 | 0.247 | 0.774 |
| 09bfde0530745af3496e0fd4fa1f5456 | -0.515 | 0.339 | 0.854 |
| c7d4efe5014249cc589da8f8168135ae | -0.500 | 0.500 | 1.000 |
| 525483b13a2039998db8b5fc2194ec46 | -0.480 | 0.520 | 1.000 |
| 489b282530bab4dbb03e15bcbfd3a9d4 | -0.468 | 0.416 | 0.884 |
| 8cca44b8b938222acc321358bb752ad7 | -0.459 | 0.524 | 0.984 |
| af0c470ca29b92c806c3bd1105e1af99 | -0.459 | 0.541 | 1.000 |
| 37d908390d99d9112dfe4d1d3a1ec36c | -0.448 | 0.527 | 0.975 |
| 07430081cb761a84518da9de7c488f40 | -0.439 | 0.556 | 0.995 |

### Full Default 相对 RRF 退步（A6_RAG_V2_FULL_DEFAULT vs A3_DENSE_LEXICAL_RRF）

- 退步题数：87

| qid | nDCG Δ | a nDCG | b nDCG |
|---|---|---|---|
| 2f33c024c6f6a19a837310f96a305f21 | -0.685 | 0.315 | 1.000 |
| abc0c5faa2dd4498c4f15d4750061b86 | -0.631 | 0.000 | 0.631 |
| e968a27390aa78ff4a3c8b536336a24f | -0.569 | 0.431 | 1.000 |
| d4a57dba2649220b958d022049f8af71 | -0.490 | 0.387 | 0.877 |
| ce6d5e1ab8f9d9124e5481580d8f6a68 | -0.453 | 0.469 | 0.922 |
| dbc80c607b320892573b67fdfdd33252 | -0.437 | 0.482 | 0.920 |
| d0a65e634128159fcb95d0e2bbb6c0a1 | -0.433 | 0.000 | 0.433 |
| 086bcf6f040ddc99d6cd58b2cca29b58 | -0.420 | 0.457 | 0.877 |
| fe04d1d516e979438d204eac666b6052 | -0.397 | 0.339 | 0.737 |
| fff04947fe9f777ab0452adc94a82d55 | -0.387 | 0.000 | 0.387 |
| 90644eb89a32ac73ac6af99d1b43e471 | -0.373 | 0.437 | 0.811 |
| 493bedb53e45deadb091f6cb382915cf | -0.369 | 0.631 | 1.000 |
| d0a16baf7f577376ecb6953683b22762 | -0.346 | 0.339 | 0.685 |
| f0205a479f8540a6bbf1f74310942183 | -0.327 | 0.000 | 0.327 |
| 0dba019610f49a39e34ddf1e562204ab | -0.315 | 0.000 | 0.315 |

### 句子窗口相对 RRF 退步（A4_HYBRID_SENTENCE_WINDOW vs A3_DENSE_LEXICAL_RRF）

- 退步题数：0

### 父子回读相对 RRF 退步（A5_HYBRID_PARENT_CHILD vs A3_DENSE_LEXICAL_RRF）

- 退步题数：0

## Benchmark B：RGB（B1 官方固定证据模式）

- 生成调用失败：0/270

### Negative Rejection：错误自信回答明细

- 正确拒答 24/30；错误自信回答 6
- id=9 query=2022年春运首日火车票什么时候开售 预测前60字：2022年春运首日（1月17日）的火车票于**2022年1月3日**开售，按火车票提前15天发售的安排。
- id=116 query=汤臣倍健2022年上半年的营收为多少？ 预测前60字：根据文档中“广告费4.34亿元，占营业收入比重10.29%”以及“仅6个月”的表述推算，汤臣倍健2022年上半年营收约为
- id=138 query=哪个国家主办了2022年的“中国旅游文化周”？ 预测前60字：根据文档，2022年“海外中国旅游文化周”由文化和旅游部指导、在全球联动举办，因此可理解为中国主办。
- id=143 query=2022年中秋假期国内旅游出游人次和收入 预测前60字：根据文档数据推算，2022年中秋假期（9月10日—12日，共3天）：

- **国内旅游出游人次**：单日约0.24亿人
- id=177 query=本泽马无法参加2022年世界杯的原因 预测前60字：提供文档的文档存在事实性错误。

关于“本泽马无法参加2022年世界杯的原因”，文档没有明确、准确说明；文档中“被送走”
- id=196 query=2023年春晚吉祥物是什么 预测前60字：文档中明确提到的是：2023年**北京台（北京卫视）春晚**吉祥物为“**兔小蕊**”。  
如果你问的是**央视春晚*

### Counterfactual：接受 fakeAnswer 且未识别错误的明细

- 接受错误答案：0/30

### Information Integration：未同时覆盖两个答案的明细

- 未完全整合：5/30
- id=18 label=[-1] query=2022和2023年春节档票房冠军分别哪部电影
- id=55 label=[-1] query=2023年IRONMAN男子和女子世界锦标赛分别在哪举行
- id=62 label=[-1] query=2023年澳网男单和温单男单冠军是谁？
- id=64 label=[1, 0] query=谷歌和百度推出的类ChatGPT是什么？
- id=86 label=[1, 1, 1, 0] query=《好好说话》的主演有哪些？

### Noise Robustness：分档表现

- noise=0.2: n=60 accuracy=0.967
- noise=0.6: n=60 accuracy=0.950
- noise=0.8: n=60 accuracy=0.950
