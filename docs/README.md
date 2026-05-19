# EmotionHub 指南

## 快速启动

当前项目有两种推荐启动方式。

### 1. 全量 Docker 运行

适合直接体验完整项目：

```powershell
cd D:\EmotionHub
.\start-docker.ps1
```

如果你需要重建镜像，再显式执行：

```powershell
cd D:\EmotionHub
.\start-docker.ps1 -BuildImages
```

也可以手动执行：

```powershell
cd D:\EmotionHub
docker compose up -d
```

访问地址：

- 前端：http://localhost:3000
- 后端健康检查：http://localhost:8080/api/test/hello

说明：

- `start-docker.ps1` 会等待前后端就绪后自动打开浏览器。
- 默认不会每次都重建镜像。
- Docker 会同时启动 MySQL、Redis、backend、frontend。
- 前端容器已经内置 `/api` 反向代理。
- `prod` 配置下 API 文档默认关闭。

### 2. 本地开发运行

适合日常开发调试：

```powershell
cd D:\EmotionHub
docker compose up -d mysql redis
```

```powershell
cd D:\EmotionHub\backend\emotion-hub-web
mvn spring-boot:run
```

```powershell
cd D:\EmotionHub\frontend
npm install
npm run dev
```

访问地址：

- 前端：http://localhost:3000
- 后端：http://localhost:8080
- API 文档：http://localhost:8080/doc.html

不要同时使用“全量 Docker 运行”和“本地开发运行”，否则会抢占 `3000` 和 `8080` 端口。

## 数据库初始化

当前正常启动不需要手工执行 `database/init.sql`。

数据库初始化流程已经改为：

- Flyway 自动创建表结构和示例数据
- Docker 启动时自动执行 MySQL 权限脚本

如果你需要重置测试数据，直接执行：

```powershell
cd D:\EmotionHub
docker compose down -v
docker compose up -d --build
```
## LightGBM Ranker模型部署与加载
```powershell
cd ml
docker-compose up -d ranker-serving
```
注：数据积累后切换真实数据重训练
```powershell
python data_export.py
python train.py --source real
curl -X POST http://localhost:5000/reload #热加载新模型，无需重启
```
## 测试账号

所有测试账号密码都是：`password123`

| 用户名 | 昵称 | 邮箱 |
| --- | --- | --- |
| alice_chen | Alice Chen | alice@example.com |
| bob_wang | Bob Wang | bob@example.com |
| carol_liu | Carol Liu | carol@example.com |
| david_zhang | David Zhang | david@example.com |
| emma_li | Emma Li | emma@example.com |

## 参考文档

- [项目启动指南](./项目启动指南.md)
- [测试账号](./测试账号.md)
- [EmotionHub 产品答辩 100 题（含参考答案）](./EmotionHub产品答辩100题.md)
- [后端说明](../backend/README.md)
