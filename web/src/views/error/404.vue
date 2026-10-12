<template>
  <div class="page">
    <el-result icon="warning" title="页面不存在" sub-title="地址写错了，或者你没有这个页面的权限">
      <template #extra>
        <el-button type="primary" @click="$router.replace('/')">回首页</el-button>
      </template>
    </el-result>
  </div>
</template>
