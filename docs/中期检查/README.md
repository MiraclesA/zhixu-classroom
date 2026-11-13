# 中期检查代码说明

本目录依据“项目中期总结”整理，只讲解当前已经整合、可运行的 `zhixu-classroom` 代码。**每份后端文档只对应一个业务功能**，不引用其他项目的类名或伪代码。

## 最重要的核心功能

> ⭐ **[考勤签到与自动缺勤](02-考勤签到与自动缺勤【核心】.md)** 是本项目最重要的后端功能：学生签到、教师修正、请假覆盖、30 分钟后自动补写缺勤和考勤统计都以它为中心。

## 后端功能文档

| 优先级 | 文档 | 实际代码入口 |
| --- | --- | --- |
| ⭐ 核心 | [考勤签到与自动缺勤](02-考勤签到与自动缺勤【核心】.md) | `SchoolService.checkIn`、`finalizeAbsences`、`changeAttendance` |
| 重要 | [账号认证与会话](01-登录功能（后端）.md) | `SchoolService.login`、`AuthFilter` |
| 重要 | [课表查询与课次调整](03-课表查询与课次调整.md) | `SchoolService.timetable`、`reschedule` |
| 一般 | [课堂举手](04-课堂举手.md) | `SchoolService.hand` |
| 重要 | [课堂消息与实时刷新](05-课堂消息与实时刷新.md) | `SchoolService.message`、`ApiController.events` |
| 一般 | [课堂评分](06-课堂评分.md) | `SchoolService.score` |
| 重要 | [请假申请](07-请假申请.md) | `SchoolService.apply` |
| 重要 | [请假审批](08-请假审批.md) | `SchoolService.decide` |
| 一般 | [请假材料附件](09-请假材料附件.md) | `saveAttachment`、`attachment` |
| 重要 | [考勤报表](10-考勤报表.md) | `SchoolService.report` |
| 一般 | [通知中心](11-通知中心.md) | `SchoolService.notifications` |

## 前端功能文档

| 文档 | 对应功能 | 实际代码入口 |
| --- | --- | --- |
| [登录界面渲染（前端）](02-登录界面渲染（前端）.md) | 登录表单渲染、提交、保存会话和进入课堂 | `auth.js`、`bridge.js`、`index.html` |

核心方法已经在源代码中用 JavaDoc 或行内注释标出职责与关键安全处理。
