# 计算机体系结构

本仓库的cpu为带mmu和clint的riscv五级流水线cpu，通过此cpu了解计算机运行的最基本机理

### RISCV

riscv手册可参考此网站： https://five-embeddev.com/

### 五级流水CPU

![avatar](cpu.png)

### MMU

+ tlb

+ ptw（可以由软件实现）

### CLINT与PLIC

+ clint

+ plic

### DMA与通道

+ 轮询

+ 中断

+ DMA与通道

### CACHE

##### 多级cache

  - L1 dcache和icache

  - L2

  - L3

##### 实现原理

  - tag index offset

  - 一路组相联

  - 多路组相联

  - 全相联

##### 跟新策略

  - 写直达

  - 写回

##### 组织方式

歧义：不同页表的相同虚拟地址映射到不同物理地址

别名：相同页表的不同虚拟地址映射到同一物理地址

  - vivt：存在歧义和别名

  - pipt：不存在歧义和别名

  - vipt：不存在歧义，但是可能存在别名，tag是页号

##### 缓存一致性协议：MESI协议

  - 状态：

    + modified

    + exclusive

    + shared

    + invalid

  - store buffer

  - invalid queue

### 总线

+ 通信协议(AXI4-lite)

+ 仲裁

### 锁的底层实现

riscv的原子指令

### 外设

通过访问数据寄存器，状态寄存器和命令寄存器控制外设

### PMU

性能监测单元



