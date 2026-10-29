知造 SIRP · 便携版（无需安装任何环境）
========================================

一、怎么启动
------------
Windows：双击 start.bat（会自己开一个窗口，首次运行需要联网下载 JRE 和数据库，约 3~5 分钟）
Linux  ：终端里执行 ./start.sh

启动成功后浏览器会自动打开 http://localhost:8080/app/
如果没自动打开，手动访问这个地址即可。

首次启动会：
  1. 下载 Java 17 运行环境（约 45MB）到 runtime/jre
  2. 下载 MariaDB 便携版（约 90MB）到 runtime/mariadb
  3. 初始化数据库到 runtime/data
  4. 生成 config/application.yml（数据库密码随机）
  5. 随机生成管理员密码，写在 data/initial-admin.txt，启动窗口也会显示

以后再启动就很快（只启动，不再下载）。

二、登录
--------
账号：admin
密码：见 data/initial-admin.txt（启动窗口也会打印）
首次登录后请到「系统 → 用户管理」修改密码，然后删掉那个文件。

三、数据在哪 / 怎么备份
--------------------
所有数据都在本目录：
  runtime/data     数据库文件（备份就拷这个目录，停服务后拷最稳）
  config/          应用配置（数据库密码、端口）
  runtime/logs/    日志（app.log 是应用，db.err.log 是数据库）
卸载 = 停掉服务，删掉整个目录。不会往系统里写任何东西（除了解压出来的文件）。

四、停止
--------
Windows：双击 stop.bat，或直接关掉启动窗口后运行 stop.bat
Linux  ：./stop.sh

五、换端口
----------
Windows：start.bat -Port 8090
Linux  ：PORT=8090 ./start.sh
数据库默认用 127.0.0.1:3307，避免和本机已装的 MySQL(3306) 冲突；
要换数据库端口：start.bat -DbPort 3308 / DB_PORT=3308 ./start.sh

六、常见问题
------------
1) 首次下载失败
   - 默认用国内镜像（清华 JRE、阿里云 MariaDB）。可手动下载后放到：
       runtime/jre.zip / runtime/mariadb.zip
     再重新启动，脚本会自动解压。
   - 也可以在 start.ps1 / start.sh 顶部改 JRE_URL / DB_URL。

2) 端口被占用
   - 应用端口：按第五节的 -Port 换一个
   - 数据库端口：-DbPort 换一个

3) 忘了管理员密码
   - 停服务，删掉 config/application.yml 与 runtime/data 会清空全部数据（谨慎）
   - 或者用另一个管理员账号在系统里重置；都没有就按上面重建

4) 杀毒软件拦截
   - 便携版会自己在目录里释放 Java 和数据库，可能被拦；把本目录加入白名单。

5) Windows 运行提示"无法加载文件，未数字签名"
   - 用 start.bat 启动即可（它已带 -ExecutionPolicy Bypass），不要直接右键运行 ps1。

七、给维护者
------------
这些脚本在仓库 tools/portable/ 下，升级版本时用 tools/make-portable.sh 重新打包：
  会产出 dist/device-hub-<版本>-portable.zip（jar + 启动脚本 + 本说明）
