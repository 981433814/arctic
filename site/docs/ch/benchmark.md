# Lakehouse 性能对比测试报告

## 测试目标
此次测试目标是比较在数据库 CDC 持续流式摄取的场景下，各个数据湖 Format 的 OLAP benchmark 性能。

## 测试环境
### 机器环境

| 编号 | 操作系统 | cpu 线程数 | 内存 | 磁盘类型 |
| ---- | -------- | --------- | ---- | -------- |
| 1    | CentOS 7 | 40        | 256  | SAS      |
| 2    | CentOS 7 | 40        | 256  | SAS      |
| 3    | CentOS 7 | 40        | 256  | SAS      |
| 4    | CentOS 7 | 40        | 256  | SAS      |
| 5    | CentOS 7 | 40        | 256  | SAS      |
| 6    | CentOS 7 | 40        | 256  | SAS      |

### 软件环境

Trino 版本：380(用于 Iceberg 和 Arctic的测试)

Presto: 274(用于Hudi的测试，Trino不支持Hudi的MOR实时查询)

Iceberg 版本：0.13  （由于开源的iceberg在trino查询中的性能非常低，所以后面的测试均采用的是内部优化过后的版本，相关优化也提交社区见: https://github.com/apache/iceberg/issues/5245, https://github.com/apache/iceberg/pull/5264）

Arctic 版本：0.3

1，2，3机器部署 Hadoop 集群包括 Hive，Yarn，HDFS，最终 Iceberg 和 Arctic 的数据是落在这三台机器上并且负责 Flink 写入任务，MySQL 服务。

4，5，6机器部署查询 Trino 查询引擎，部署了3个节点每个节点设置64G内存。

## 测试方案

### CHbenchmark

本次测试规范基于CHbenchmark，CHbenchmark是一个集成了TPCC和TPCH的混合测试标准，测试负载整体上可分成两类：

- 基于 TPC-C 的5个 OLTP 型负载：NewOrder, Payment, OrderStatus, Delivery 和 StockLevel

- 基于 TCP-H 改写的22个 OLAP 型负载，其中由于Q15查询和视图相关，故此次测试舍弃了Q15

![OLTP AND OLAP](images/chbenchmark/OLTP-OLAP.png){:height="60%" width="60%"}

### 基于TPC-C的数据构造

基于TPC-C标准，本次测试在 MySQL中构造了原始数据，数据集总共包括12张表，其中TPC-C和TPC-H表的关系如下图所示：

![TPCC AND TPCH](images/chbenchmark/TPCC-TPCH.png){:height="60%" width="60%"}

此外，各个数据表数据规模间的联系如下表所示，其中 w 表示 warehouse 的数量。可以观察到中间的 new_order, stock 等表的数据规模受到 warehouse 数量的影响，
因此在测试过程中可以通过控制 warehouse 的数量来调整数据集的规模。

![chbenchmark](images/chbenchmark/chbenchmark.png){:height="60%" width="60%"}

在本次测试中，设置 warehouse 数量为100，MySQL 数据库中对应的初始数据集大小约为10G。下表展示了初始数据集下各张表的数据记录数以及开启一小时 TPC-C 测试后各张表的数据记录变化情况。

| 表名称     | 初始数据集下表的记录数（行） | 开启一小时TPCC测试后表的记录数（行） |
| ---------- | ---------------------------- | ------------------------------------ |
| warehouse  | 100                          | 100                                  |
| item       | 100000                       | 100000                               |
| stock      | 10000000                     | 10000000                             |
| district   | 1000                         | 1000                                 |
| customer   | 3000000                      | 3000000                              |
| history    | 3000000                      | 3119285（+119285）                   |
| oorder     | 3000000                      | 3124142（+124142）                   |
| new_order  | 893709                       | 907373（+13664）                     |
| order_line | 29996774                     | 31252799（+1256025）                 |
| region     | 5                            | 5                                    |
| nation     | 62                           | 62                                   |
| supplier   | 1000                         | 1000                                 |

### 基于TPC-H的查询场景

TPC-H 面向模拟的业务系统设计了22个 Query ，受限于篇幅，这里列举前3条 Query 语句作为参考：
```
-- SQL编号：query1 
 select l_returnflag, l_linestatus, sum(l_quantity) as sum_qty, 
        sum(l_extendedprice) as sum_base_price, 
        sum(l_extendedprice * (1 - l_discount)) as sum_disc_price,
        sum(l_extendedprice * (1 - l_discount) * (1 + l_tax)) as sum_charge,
        avg(l_quantity) as avg_qty, 
        avg(l_extendedprice) as avg_price, 
        avg(l_discount) as avg_disc,
        count(*) as count_order
        from lineitem
        where l_shipdate <= date '1998-12-01' - interval ? day
        group by l_returnflag, l_linestatus
        order by l_returnflag, l_linestatus;
        
 -- SQL编号：query2 
 select s_acctbal, s_name, n_name, p_partkey, p_mfgr,
        s_address, s_phone, s_comment from part, supplier, partsupp, nation, region
        where p_partkey = ps_partkey and s_suppkey = ps_suppkey
        and p_size = ? and p_type like ? and s_nationkey = n_nationkey and n_regionkey = r_regionkey 
        and r_name = ? and ps_supplycost = (select min(ps_supplycost)
        from partsupp, supplier, nation, region
        where p_partkey = ps_partkey and s_suppkey = ps_suppkey
        and s_nationkey = n_nationkey and n_regionkey = r_regionkey and r_name = ? )
        order by s_acctbal desc, n_name, s_name, p_partkey;
        
 -- SQL编号：query3 
 select l_orderkey,
        sum(l_extendedprice * (1 - l_discount)) as revenue,
        o_orderdate, o_shippriority
        from customer, orders, lineitem
        where c_mktsegment = ? 
        and c_custkey = o_custkey and l_orderkey = o_orderkey 
        and o_orderdate < date ? and l_shipdate > date ?
        group by l_orderkey, o_orderdate, o_shippriority
        order by revenue desc, o_orderdate;
```
### 测试整体流程

1. 基于 TPC-C 标准在 MySQL 中生成初始的数据集

2. 使用 Flink-CDC 将 MySQL 中的数据导入到 Iceberg/Arctic/Hudi 表中

3. 开启 TPC-C 测试，OLTP 型负载会不断生成增量数据，同时 Flink-CDC 将这部分增量数据实时同步到 Iceberg/Arctic 表中

4. 基于 TPC-H 标准，通过 Trino 进行查询，并记录每个查询花费的时间以及所有查询的平均查询时间

![over design](images/chbenchmark/benchmark-over-design-cn.png){:height="60%" width="60%"}

## 测试结果

## 静态数据
![arctic iceberg static performence](images/chbenchmark/arctic-iceberg-100-warehouse-static-performence.png)

上图表示100个 warehouse 数据量下，纯静态数据没有更新的情况下，10个查询并发 Arctic 和 Iceberg 查询性能比较，通过上图可以看出每个 Query 的查询耗时非常接近。

## 动态持续查询性能

![Arctic Iceberg Hudi 100 warehouse performence with TPCC time](images/chbenchmark/Arctic-Iceberg-Hudi_100_warehouse_performence_with_TPCC_time.png)

在测试时间内 TPCC 持续进行，横轴表示的是查询的时间范围，纵轴表示21个查询（去除Q15）的平均时间，基础静态数据量为100个 warehouse, 查询并发数是10，其中 optimize 表示 Arctic 有小文件合并的场景，no_optimize 表示 Arctic 没有小文件合并的场景。 其中90分钟和120分钟没有 Iceberg 的数据是因为 Iceberg 已经无法跑出结果，打爆了 Trino 集群。

根据图上显示所知，在有 optimize 的情况下查询性能最好且不随同步数据时间的增长而增长，而 no_optimize 的场景下查询时间会逐渐增长，Iceberg 的性能表现最差且随着时间增长查询时间大幅增长。

具体query详情见下图：

![Arctic Iceberg Hudi 100 warehouse performence on TPCC 0-30 minutes](images/chbenchmark/Arctic-Iceberg-Hudi_100_warehouse_performence_on_TPCC_0-30_minutes.png)

![Arctic Iceberg Hudi 100 warehouse performence on TPCC 30-60 minutes](images/chbenchmark/Arctic-Iceberg-Hudi_100_warehouse_performence_on_TPCC_30-60_minutes.png)

![Arctic Iceberg Hudi 100 warehouse performence on TPCC 60-90 minutes](images/chbenchmark/Arctic-Iceberg-Hudi_100_warehouse_performence_on_TPCC_60-90_minutes.png)

![Arctic Iceberg Hudi 100 warehouse performence on TPCC 90-120 minutes](images/chbenchmark/Arctic-Iceberg-Hudi_100_warehouse_performence_on_TPCC_90-120_minutes.png)

# 小结

- 静态数据情况下 Arctic 和 Iceberg 的查询性能几乎相同，但是随着 TPCC 的进行，CDC 数据的增多，Iceberg 的查询性能急剧下降，没有 optimize 的 Arctic 缓慢下降，有 optimize 的 Arctic 性能几乎保持不变。
- Hudi 的性能因为写入任务自带 optimize，所以查询性能也能很好的收敛，总体是优于不带 optimize 的 Arctic，弱于带 optimize 的 Arctic。
   