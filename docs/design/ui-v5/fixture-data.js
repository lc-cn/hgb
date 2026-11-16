window.uxTaskFixtures=[
  {
    "id": "daily-rewards",
    "name": "每日物资领取",
    "category": "daily",
    "period": "每日",
    "scenes": [
      "courtyard"
    ],
    "impact": "仅领取所选物资，不购买额外权益",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "sources",
        "label": "领取来源",
        "type": "checks",
        "control": "checks",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "签到奖励",
          "每日物资",
          "花合战奖励"
        ],
        "required": true
      }
    ],
    "seed": {
      "sources": [
        "签到奖励",
        "每日物资"
      ],
      "priority": "10"
    },
    "steps": [
      {
        "id": "daily-rewards:1",
        "label": "打开每日奖励"
      },
      {
        "id": "daily-rewards:2",
        "label": "核对可领取奖励"
      },
      {
        "id": "daily-rewards:3",
        "label": "领取并核对结果"
      }
    ],
    "values": {
      "sources": [
        "签到奖励",
        "每日物资"
      ],
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "weekly-rewards",
    "name": "每周物资领取",
    "category": "daily",
    "period": "每周",
    "scenes": [
      "courtyard"
    ],
    "impact": "按各来源周期核对领取结果",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "sources",
        "label": "领取来源",
        "type": "checks",
        "control": "checks",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "每周奖励",
          "周期任务奖励"
        ],
        "required": true
      }
    ],
    "seed": {
      "sources": [
        "每周奖励"
      ],
      "priority": "10"
    },
    "steps": [
      {
        "id": "weekly-rewards:1",
        "label": "打开每周奖励"
      },
      {
        "id": "weekly-rewards:2",
        "label": "核对周进度和资格"
      },
      {
        "id": "weekly-rewards:3",
        "label": "领取并核对结果"
      }
    ],
    "values": {
      "sources": [
        "每周奖励"
      ],
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "jade-card",
    "name": "永久勾玉卡产出",
    "category": "daily",
    "period": "每日",
    "scenes": [
      "courtyard"
    ],
    "impact": "领取已拥有权益的产出，不购买勾玉卡",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "scope",
        "label": "领取范围",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "仅永久勾玉卡产出"
        ]
      },
      {
        "key": "eligibility",
        "label": "权益条件",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "核对已拥有权益后领取"
        ]
      }
    ],
    "seed": {
      "scope": "仅永久勾玉卡产出",
      "eligibility": "核对已拥有权益后领取",
      "priority": "20"
    },
    "common": true,
    "steps": [
      {
        "id": "jade-card:1",
        "label": "打开永久勾玉卡"
      },
      {
        "id": "jade-card:2",
        "label": "核对权益及已有产出"
      },
      {
        "id": "jade-card:3",
        "label": "领取产出并核对"
      }
    ],
    "values": {
      "scope": "仅永久勾玉卡产出",
      "eligibility": "核对已拥有权益后领取",
      "priority": "20"
    }
  },
  {
    "id": "flower",
    "name": "花合战奖励",
    "category": "daily",
    "period": "每日",
    "scenes": [
      "courtyard"
    ],
    "impact": "奖励领取与主动完成任务分别配置",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "mode",
        "label": "处理范围",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "仅领取奖励",
          "完成已选择任务后领取"
        ]
      },
      {
        "key": "targets",
        "label": "任务范围",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "mode": "仅领取奖励",
      "targets": "已达成奖励",
      "priority": "10"
    },
    "steps": [
      {
        "id": "flower:1",
        "label": "打开花合战"
      },
      {
        "id": "flower:2",
        "label": "核对已达成奖励"
      },
      {
        "id": "flower:3",
        "label": "领取并核对结果"
      }
    ],
    "values": {
      "mode": "仅领取奖励",
      "targets": "已达成奖励",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "bounty",
    "name": "悬赏封印",
    "category": "daily",
    "period": "每日",
    "scenes": [
      "courtyard"
    ],
    "impact": "协作对象与资源使用需明确",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "mode",
        "label": "任务方式",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "个人任务",
          "指定协作任务"
        ]
      },
      {
        "key": "count",
        "label": "目标任务数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "partner",
        "label": "协作对象",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "mode": "个人任务",
      "count": "3",
      "partner": "",
      "priority": "10"
    },
    "steps": [
      {
        "id": "bounty:1",
        "label": "打开悬赏封印"
      },
      {
        "id": "bounty:2",
        "label": "核对目标与完成进度"
      },
      {
        "id": "bounty:3",
        "label": "按配置完成并核对奖励"
      }
    ],
    "values": {
      "mode": "个人任务",
      "count": "3",
      "partner": "",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "pet",
    "name": "宠物照料",
    "category": "daily",
    "period": "每日",
    "scenes": [
      "courtyard"
    ],
    "impact": "只使用指定照料范围与资源",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "actions",
        "label": "照料范围",
        "type": "checks",
        "control": "checks",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "领取奖励",
          "投喂"
        ],
        "required": true
      },
      {
        "key": "food",
        "label": "允许使用的食物",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "actions": [
        "领取奖励"
      ],
      "food": "仅已指定食物",
      "priority": "10"
    },
    "steps": [
      {
        "id": "pet:1",
        "label": "打开小动物"
      },
      {
        "id": "pet:2",
        "label": "核对可领取物资"
      },
      {
        "id": "pet:3",
        "label": "领取并核对结果"
      }
    ],
    "values": {
      "actions": [
        "领取奖励"
      ],
      "food": "仅已指定食物",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "shop-general",
    "name": "一般商店兑换",
    "category": "shops",
    "period": "按需",
    "scenes": [
      "courtyard",
      "shop"
    ],
    "impact": "消耗所选支付资源，遵守数量、预算及保留余额",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "shop",
        "label": "目标商店",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "item",
        "label": "兑换商品",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "currency",
        "label": "支付资源",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "quantity",
        "label": "每周期数量",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "消费上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "reserve",
        "label": "保留余额",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "shop": "所选商店",
      "item": "指定物资",
      "currency": "指定货币",
      "quantity": "1",
      "budget": "1000",
      "reserve": "500",
      "priority": "10"
    },
    "steps": [
      {
        "id": "shop-general:1",
        "label": "打开配置商店"
      },
      {
        "id": "shop-general:2",
        "label": "核对商品、限购与预算"
      },
      {
        "id": "shop-general:3",
        "label": "兑换并核对结果"
      }
    ],
    "values": {
      "shop": "所选商店",
      "item": "指定物资",
      "currency": "指定货币",
      "quantity": "1",
      "budget": "1000",
      "reserve": "500",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "shop-wed",
    "name": "周三商店兑换",
    "category": "shops",
    "period": "周三",
    "scenes": [
      "courtyard",
      "shop"
    ],
    "impact": "按所选商店开放条件兑换，不自动刷新或加购",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "shop",
        "label": "目标商店",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "item",
        "label": "兑换商品",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "currency",
        "label": "支付资源",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "quantity",
        "label": "本周期数量",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "消费上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "reserve",
        "label": "保留余额",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "shop": "周三商店",
      "item": "指定物资",
      "currency": "指定货币",
      "quantity": "1",
      "budget": "1000",
      "reserve": "500",
      "priority": "10"
    },
    "common": true,
    "steps": [
      {
        "id": "shop-wed:1",
        "label": "打开配置商店"
      },
      {
        "id": "shop-wed:2",
        "label": "核对商品、限购与预算"
      },
      {
        "id": "shop-wed:3",
        "label": "兑换并核对结果"
      }
    ],
    "values": {
      "shop": "周三商店",
      "item": "指定物资",
      "currency": "指定货币",
      "quantity": "1",
      "budget": "1000",
      "reserve": "500",
      "priority": "10"
    }
  },
  {
    "id": "shop-sat",
    "name": "周六商店兑换",
    "category": "shops",
    "period": "周六",
    "scenes": [
      "courtyard",
      "shop"
    ],
    "impact": "只兑换已配置商品，不以价格变化自动替换",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "shop",
        "label": "目标商店",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "item",
        "label": "兑换商品",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "currency",
        "label": "支付资源",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "quantity",
        "label": "本周期数量",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "消费上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "reserve",
        "label": "保留余额",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "shop": "周六商店",
      "item": "指定物资",
      "currency": "指定货币",
      "quantity": "1",
      "budget": "1000",
      "reserve": "500",
      "priority": "10"
    },
    "steps": [
      {
        "id": "shop-sat:1",
        "label": "打开配置商店"
      },
      {
        "id": "shop-sat:2",
        "label": "核对商品、限购与预算"
      },
      {
        "id": "shop-sat:3",
        "label": "兑换并核对结果"
      }
    ],
    "values": {
      "shop": "周六商店",
      "item": "指定物资",
      "currency": "指定货币",
      "quantity": "1",
      "budget": "1000",
      "reserve": "500",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "soul-run",
    "name": "御魂副本",
    "category": "dungeons",
    "period": "按需",
    "scenes": [
      "courtyard",
      "dungeon"
    ],
    "impact": "使用已设体力／门票，达到目标或资源条件后停止",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "stage",
        "label": "副本与难度",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "team",
        "label": "队伍方式",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "单人",
          "已配置的组队方式"
        ]
      },
      {
        "key": "preset",
        "label": "阵容预设",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "体力／门票上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "25"
    },
    "common": true,
    "steps": [
      {
        "id": "soul-run:1",
        "label": "选择配置副本与难度"
      },
      {
        "id": "soul-run:2",
        "label": "核对队伍及体力门票"
      },
      {
        "id": "soul-run:3",
        "label": "挑战并核对结算"
      }
    ],
    "values": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "25"
    }
  },
  {
    "id": "awake-run",
    "name": "觉醒副本",
    "category": "dungeons",
    "period": "按需",
    "scenes": [
      "courtyard",
      "dungeon"
    ],
    "impact": "使用已设体力／门票，达到目标或资源条件后停止",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "stage",
        "label": "副本与难度",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "team",
        "label": "队伍方式",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "单人",
          "已配置的组队方式"
        ]
      },
      {
        "key": "preset",
        "label": "阵容预设",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "体力／门票上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "10"
    },
    "common": false,
    "steps": [
      {
        "id": "awake-run:1",
        "label": "选择配置副本与难度"
      },
      {
        "id": "awake-run:2",
        "label": "核对队伍及体力门票"
      },
      {
        "id": "awake-run:3",
        "label": "挑战并核对结算"
      }
    ],
    "values": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "10"
    }
  },
  {
    "id": "explore-run",
    "name": "探索",
    "category": "dungeons",
    "period": "按需",
    "scenes": [
      "courtyard",
      "dungeon"
    ],
    "impact": "使用已设体力／门票，达到目标或资源条件后停止",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "stage",
        "label": "副本与难度",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "team",
        "label": "队伍方式",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "单人",
          "已配置的组队方式"
        ]
      },
      {
        "key": "preset",
        "label": "阵容预设",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "体力／门票上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "40"
    },
    "common": false,
    "steps": [
      {
        "id": "explore-run:1",
        "label": "选择配置副本与难度"
      },
      {
        "id": "explore-run:2",
        "label": "核对队伍及体力门票"
      },
      {
        "id": "explore-run:3",
        "label": "挑战并核对结算"
      }
    ],
    "values": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "40"
    }
  },
  {
    "id": "spirit-run",
    "name": "御灵",
    "category": "dungeons",
    "period": "按需",
    "scenes": [
      "courtyard",
      "dungeon"
    ],
    "impact": "使用已设体力／门票，达到目标或资源条件后停止",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "stage",
        "label": "副本与难度",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "team",
        "label": "队伍方式",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "单人",
          "已配置的组队方式"
        ]
      },
      {
        "key": "preset",
        "label": "阵容预设",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "体力／门票上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "10"
    },
    "common": false,
    "steps": [
      {
        "id": "spirit-run:1",
        "label": "选择配置副本与难度"
      },
      {
        "id": "spirit-run:2",
        "label": "核对队伍及体力门票"
      },
      {
        "id": "spirit-run:3",
        "label": "挑战并核对结算"
      }
    ],
    "values": {
      "stage": "已选择难度",
      "team": "单人",
      "preset": "现有阵容",
      "count": "10",
      "budget": "100",
      "priority": "10"
    }
  },
  {
    "id": "soul-enhance",
    "name": "御魂强化",
    "category": "souls",
    "period": "按需",
    "scenes": [
      "courtyard",
      "souls"
    ],
    "impact": "只消耗指定素材与预算；阶段结束后核对等级与属性",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "target",
        "label": "目标御魂／筛选条件",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "stage",
        "label": "强化目标",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "指定等级",
          "分阶段强化"
        ]
      },
      {
        "key": "level",
        "label": "目标等级／阶段",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "material",
        "label": "允许使用的素材",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "金币与素材预算",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "stopRule",
        "label": "继续／停止条件",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "protect",
        "label": "保护对象",
        "type": "checks",
        "control": "checks",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "锁定御魂",
          "装备中御魂",
          "其他任务中的御魂"
        ]
      }
    ],
    "seed": {
      "target": "用户指定对象",
      "stage": "分阶段强化",
      "level": "3",
      "material": "指定强化素材",
      "budget": "10000",
      "stopRule": "每阶段核对后按规则继续",
      "protect": [
        "锁定御魂",
        "装备中御魂",
        "其他任务中的御魂"
      ],
      "priority": "10"
    },
    "common": true,
    "steps": [
      {
        "id": "soul-enhance:1",
        "label": "筛选待强化御魂"
      },
      {
        "id": "soul-enhance:2",
        "label": "核对素材预算与保护对象"
      },
      {
        "id": "soul-enhance:3",
        "label": "分阶段强化并核对属性"
      }
    ],
    "values": {
      "target": "用户指定对象",
      "stage": "分阶段强化",
      "level": "3",
      "material": "指定强化素材",
      "budget": "10000",
      "stopRule": "每阶段核对后按规则继续",
      "protect": [
        "锁定御魂",
        "装备中御魂",
        "其他任务中的御魂"
      ],
      "priority": "10"
    }
  },
  {
    "id": "soul-sort",
    "name": "御魂整理",
    "category": "souls",
    "period": "按需",
    "scenes": [
      "courtyard",
      "souls"
    ],
    "impact": "只执行明确选择的操作，筛选命中不等于允许消耗",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "filter",
        "label": "筛选范围",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "keep",
        "label": "保留／排除规则",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "operation",
        "label": "整理操作",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "筛选查看",
          "标记",
          "锁定",
          "解锁",
          "消耗性处置（需明确规则）"
        ]
      },
      {
        "key": "protect",
        "label": "保护对象",
        "type": "checks",
        "control": "checks",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "锁定御魂",
          "装备中御魂",
          "正在强化的御魂"
        ]
      }
    ],
    "seed": {
      "filter": "按位置、套装与属性筛选",
      "keep": "保留用户指定对象",
      "operation": "筛选查看",
      "protect": [
        "锁定御魂",
        "装备中御魂",
        "正在强化的御魂"
      ],
      "priority": "10"
    },
    "steps": [
      {
        "id": "soul-sort:1",
        "label": "读取并筛选御魂"
      },
      {
        "id": "soul-sort:2",
        "label": "排除保护对象"
      },
      {
        "id": "soul-sort:3",
        "label": "整理并核对结果"
      }
    ],
    "values": {
      "filter": "按位置、套装与属性筛选",
      "keep": "保留用户指定对象",
      "operation": "筛选查看",
      "protect": [
        "锁定御魂",
        "装备中御魂",
        "正在强化的御魂"
      ],
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "realm-claim",
    "name": "结界物资收取",
    "category": "guild",
    "period": "每日",
    "scenes": [
      "courtyard"
    ],
    "impact": "核对实际可收取物资，不自动挂卡",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "sources",
        "label": "收取范围",
        "type": "checks",
        "control": "checks",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "结界物资",
          "到期结界卡产出"
        ],
        "required": true
      }
    ],
    "seed": {
      "sources": [
        "结界物资"
      ],
      "priority": "10"
    },
    "steps": [
      {
        "id": "realm-claim:1",
        "label": "打开结界"
      },
      {
        "id": "realm-claim:2",
        "label": "核对可收取物资"
      },
      {
        "id": "realm-claim:3",
        "label": "收取并核对产出"
      }
    ],
    "values": {
      "sources": [
        "结界物资"
      ],
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "realm-card",
    "name": "结界挂卡",
    "category": "guild",
    "period": "按需",
    "scenes": [
      "courtyard"
    ],
    "impact": "仅使用指定卡片，卡片选择与好友行为分开",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "cards",
        "label": "允许的卡片范围",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "replace",
        "label": "到期处理",
        "type": "select",
        "control": "select",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "options": [
          "核对收取后按规则挂卡",
          "等待手动选择"
        ]
      },
      {
        "key": "partner",
        "label": "好友／合作条件",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "cards": "用户指定卡片",
      "replace": "等待手动选择",
      "partner": "不包含额外好友操作",
      "priority": "10"
    },
    "steps": [
      {
        "id": "realm-card:1",
        "label": "打开结界卡设置"
      },
      {
        "id": "realm-card:2",
        "label": "核对允许卡片与当前结界"
      },
      {
        "id": "realm-card:3",
        "label": "挂卡并核对结果"
      }
    ],
    "values": {
      "cards": "用户指定卡片",
      "replace": "等待手动选择",
      "partner": "不包含额外好友操作",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "realm-raid",
    "name": "个人结界突破",
    "category": "guild",
    "period": "按需",
    "scenes": [
      "courtyard"
    ],
    "impact": "按资格、目标与资源条件执行，不扩展合作权限",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "preset",
        "label": "阵容条件",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "stopRule",
        "label": "停止条件",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "count": "3",
      "preset": "使用现有阵容",
      "stopRule": "达到目标或条件不足时结束",
      "priority": "10"
    },
    "steps": [
      {
        "id": "realm-raid:1",
        "label": "打开个人结界突破"
      },
      {
        "id": "realm-raid:2",
        "label": "核对目标与阵容"
      },
      {
        "id": "realm-raid:3",
        "label": "突破并核对结算"
      }
    ],
    "values": {
      "count": "3",
      "preset": "使用现有阵容",
      "stopRule": "达到目标或条件不足时结束",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "guild-raid",
    "name": "寮突破",
    "category": "guild",
    "period": "按需",
    "scenes": [
      "courtyard"
    ],
    "impact": "按资格、目标与资源条件执行，不扩展合作权限",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "preset",
        "label": "阵容条件",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "stopRule",
        "label": "停止条件",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "count": "3",
      "preset": "使用现有阵容",
      "stopRule": "达到目标或条件不足时结束",
      "priority": "10"
    },
    "steps": [
      {
        "id": "guild-raid:1",
        "label": "打开寮突破"
      },
      {
        "id": "guild-raid:2",
        "label": "核对资格与目标"
      },
      {
        "id": "guild-raid:3",
        "label": "突破并核对结算"
      }
    ],
    "values": {
      "count": "3",
      "preset": "使用现有阵容",
      "stopRule": "达到目标或条件不足时结束",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "hunt",
    "name": "狩猎战",
    "category": "guild",
    "period": "定时",
    "scenes": [
      "courtyard"
    ],
    "impact": "按资格、目标与资源条件执行，不扩展合作权限",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "preset",
        "label": "阵容条件",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      },
      {
        "key": "stopRule",
        "label": "停止条件",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": ""
      }
    ],
    "seed": {
      "count": "3",
      "preset": "使用现有阵容",
      "stopRule": "达到目标或条件不足时结束",
      "priority": "10"
    },
    "steps": [
      {
        "id": "hunt:1",
        "label": "打开狩猎战"
      },
      {
        "id": "hunt:2",
        "label": "核对开放状态与阵容"
      },
      {
        "id": "hunt:3",
        "label": "挑战并核对结算"
      }
    ],
    "values": {
      "count": "3",
      "preset": "使用现有阵容",
      "stopRule": "达到目标或条件不足时结束",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "event-repeat",
    "name": "已适配活动",
    "category": "activities",
    "period": "活动",
    "scenes": [
      "courtyard"
    ],
    "impact": "活动任务使用同一资源与停止规则",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "event",
        "label": "目标活动",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "goal",
        "label": "阶段／奖励目标",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "count",
        "label": "目标次数",
        "type": "number",
        "control": "number",
        "min": 1,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "活动资源上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "event": "已配置活动",
      "goal": "用户指定阶段",
      "count": "5",
      "budget": "10",
      "priority": "10"
    },
    "steps": [
      {
        "id": "event-repeat:1",
        "label": "打开所选活动关卡"
      },
      {
        "id": "event-repeat:2",
        "label": "核对资源与队伍"
      },
      {
        "id": "event-repeat:3",
        "label": "执行刷取并核对结算"
      }
    ],
    "values": {
      "event": "已配置活动",
      "goal": "用户指定阶段",
      "count": "5",
      "budget": "10",
      "priority": "10"
    },
    "common": false
  },
  {
    "id": "event-auto",
    "name": "自主理解新活动",
    "category": "activities",
    "period": "活动",
    "scenes": [
      "courtyard"
    ],
    "needsModel": true,
    "impact": "只在用户目标和允许资源内提出步骤，未知规则需说明",
    "fields": [
      {
        "key": "priority",
        "label": "优先级",
        "type": "number",
        "control": "number",
        "min": null,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "event",
        "label": "活动入口／名称",
        "type": "text",
        "control": "text",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "goal",
        "label": "希望完成的目标",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      },
      {
        "key": "budget",
        "label": "门票／体力／货币上限",
        "type": "number",
        "control": "number",
        "min": 0,
        "max": null,
        "step": 1,
        "unit": "",
        "required": true
      },
      {
        "key": "allowed",
        "label": "允许的操作范围",
        "type": "textarea",
        "control": "textarea",
        "min": null,
        "max": null,
        "step": null,
        "unit": "",
        "required": true
      }
    ],
    "seed": {
      "event": "当前新活动",
      "goal": "完成用户指定阶段",
      "budget": "10",
      "allowed": "读取规则并在配置资源范围内执行",
      "priority": "10"
    },
    "steps": [
      {
        "id": "event-auto:1",
        "label": "读取活动规则"
      },
      {
        "id": "event-auto:2",
        "label": "核对允许目标与资源"
      },
      {
        "id": "event-auto:3",
        "label": "完成所选阶段并核对"
      }
    ],
    "values": {
      "event": "当前新活动",
      "goal": "完成用户指定阶段",
      "budget": "10",
      "allowed": "读取规则并在配置资源范围内执行",
      "priority": "10"
    },
    "common": false
  }
];
