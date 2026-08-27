<template>
  <section class="pool-intro">
    <div class="intro-copy">
      <div class="eyebrow">知识库日常维护</div>
      <h1>把没答好的问题，变成下次能答好的知识</h1>
      <p>系统已经自动收集问题。你只要按顺序完成确认、补充知识和验证效果，不需要理解技术参数。</p>
    </div>
    <el-popover placement="bottom-end" :width="320" trigger="click">
      <template #reference>
        <el-button plain>第一次使用？</el-button>
      </template>
      <div class="guide-popover">
        <strong>建议从“确认问题”开始</strong>
        <p>判断系统当时的处理是否合适，再把相似问题整理成一条 FAQ。发布后运行效果验证即可。</p>
        <p>不确定时可以只填写必填项，系统已帮你预选了当时的处理方式。</p>
      </div>
    </el-popover>
  </section>

  <nav class="workflow-nav" aria-label="问题改进流程">
    <button
      v-for="(step, index) in workflowSteps"
      :key="step.value"
      type="button"
      class="workflow-step"
      :class="{ active: activeStage === step.value, done: workflowStepDone(step.value) }"
      @click="activeStage = step.value"
    >
      <span class="step-number">{{ index + 1 }}</span>
      <span class="step-copy">
        <strong>{{ step.label }}</strong>
        <small>{{ step.description }}</small>
      </span>
      <span v-if="step.value === 'problems'" class="step-count">{{ quality.pendingReviewQuestionCount || 0 }} 条待确认</span>
      <span v-else-if="step.value === 'organize' && pendingDraftCount" class="step-count">{{ pendingDraftCount }} 条草稿</span>
    </button>
  </nav>

  <el-card v-show="activeStage !== 'organize'" class="work-card">
    <template #header>
      <div class="section-heading">
        <div>
          <h2>{{ activeStage === 'report' ? '第三步：验证改进效果' : '第一步：确认哪些问题需要改进' }}</h2>
          <p v-if="activeStage === 'problems'">优先处理待确认问题。展开一行可以查看原因和系统给出的改进建议。</p>
          <p v-else>查看整体处理效果；发布新 FAQ 后，再运行一次验证。</p>
        </div>
        <div v-if="activeStage === 'problems'" class="header-actions">
          <el-radio-group :model-value="reviewOnly ? 'review' : 'all'" @change="changeQuestionScope">
            <el-radio-button value="review">待我确认</el-radio-button>
            <el-radio-button value="all">全部问题</el-radio-button>
          </el-radio-group>
        </div>
        <div v-else class="header-actions">
          <el-button type="primary" :loading="regressionRunning" @click="runRegression">运行效果验证</el-button>
        </div>
      </div>
    </template>
    <section v-show="activeStage === 'report'" v-loading="qualityLoading" class="quality-overview">
      <dl class="quality-metrics">
        <div>
          <dt>待处理问题</dt>
          <dd>{{ quality.pendingQuestionCount || 0 }}</dd>
        </div>
        <div>
          <dt>累计发生次数</dt>
          <dd>{{ quality.totalOccurrenceCount || 0 }}</dd>
        </div>
        <div>
          <dt>问题处理率</dt>
          <dd>{{ percentage(quality.resolutionRate) }}</dd>
        </div>
        <div>
          <dt>最近验证通过率</dt>
          <dd>{{ latestRegression ? percentage(latestRegression.passRate) : '-' }}</dd>
        </div>
        <div>
          <dt>待确认问题</dt>
          <dd>{{ quality.pendingReviewQuestionCount || 0 }}</dd>
        </div>
        <div>
          <dt>处理判断准确率</dt>
          <dd>{{ quality.reviewedQuestionCount ? percentage(quality.decisionAccuracy) : '-' }}</dd>
        </div>
      </dl>
      <el-alert
        v-if="quality.pendingReviewQuestionCount"
        class="review-entry"
        type="warning"
        :closable="false"
        show-icon
      >
        <template #title>
          <span>有 {{ quality.pendingReviewQuestionCount }} 条问题等待确认</span>
          <el-button
            class="review-entry-button"
            size="small"
            type="warning"
            plain
            :loading="loading"
            @click="showPendingReviews"
          >立即确认</el-button>
        </template>
      </el-alert>
      <div class="quality-details">
        <div class="quality-block">
          <div class="quality-block-heading">
            <strong>问题原因分布</strong>
            <span>按发生次数统计</span>
          </div>
          <div v-if="quality.triggerCounts?.length" class="trigger-list">
            <div v-for="item in quality.triggerCounts" :key="item.triggerType" class="trigger-row">
              <span>{{ badCaseTypeLabel(item.triggerType) }}</span>
              <el-tag size="small" type="info">{{ item.count }}</el-tag>
            </div>
          </div>
          <el-empty v-else description="暂无问题原因" :image-size="44" />
        </div>
        <div class="quality-block regression-history">
          <div class="quality-block-heading">
            <strong>最近效果验证</strong>
            <span v-if="quality.passRateDelta != null" :class="quality.passRateDelta >= 0 ? 'rate-up' : 'rate-down'">
              较上次 {{ signedPercentage(quality.passRateDelta) }}
            </span>
            <span v-else>至少验证两次后显示变化</span>
          </div>
          <el-table v-if="quality.regressionHistory?.length" :data="quality.regressionHistory" size="small">
            <el-table-column label="时间" min-width="130">
              <template #default="{ row }">{{ formatDateTime(row.createTime) }}</template>
            </el-table-column>
            <el-table-column label="通过率" width="90">
              <template #default="{ row }">{{ percentage(row.passRate) }}</template>
            </el-table-column>
            <el-table-column label="结果" width="76">
              <template #default="{ row }">
                <el-tag size="small" :type="row.passed ? 'success' : 'danger'">{{ row.passed ? '通过' : '未通过' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="failedCaseCount" label="失败" width="64" />
          </el-table>
          <el-empty v-else description="暂无回归记录" :image-size="44" />
        </div>
      </div>
      <div v-if="quality.decisionCounts?.length" class="decision-distribution">
        <div class="quality-block-heading">
          <strong>系统处理方式</strong>
          <span>按问题出现次数统计</span>
        </div>
        <div class="decision-list">
          <div v-for="item in quality.decisionCounts" :key="item.decision" class="decision-row">
            <span>{{ decisionLabel(item.decision) }}</span>
            <el-tag size="small" :type="decisionTagType(item.decision)">{{ item.count }}</el-tag>
          </div>
        </div>
      </div>
      <div v-if="quality.repeatedFailures?.length" class="repeated-failures">
        <div class="quality-block-heading">
          <strong>反复失败问题</strong>
          <span>同一问法在多次回归中未通过</span>
        </div>
        <div class="repeated-list">
          <div v-for="item in quality.repeatedFailures" :key="item.question" class="repeated-item">
            <span class="repeated-question">{{ item.question }}</span>
            <el-tag size="small" type="danger">失败 {{ item.failedRunCount }} 次</el-tag>
          </div>
        </div>
      </div>
    </section>
    <div v-if="activeStage === 'report' && regressionReport" class="regression-result">
      <el-alert
        :title="regressionReport.passed ? '回归验证通过' : `回归验证发现 ${regressionReport.failedCaseCount} 个问题`"
        :type="regressionReport.passed ? 'success' : 'error'"
        :closable="false"
        show-icon
      />
      <div class="regression-metrics">
        <el-tag>已发布FAQ {{ regressionReport.publishedDraftCount }}</el-tag>
        <el-tag type="info">样本 {{ regressionReport.datasetCaseCount }}</el-tag>
        <el-tag type="success">通过 {{ regressionReport.passedCaseCount }}</el-tag>
        <el-tag :type="regressionReport.failedCaseCount ? 'danger' : 'info'">失败 {{ regressionReport.failedCaseCount }}</el-tag>
        <el-tag v-if="regressionReport.truncated" type="warning">本次执行前 {{ regressionReport.executedCaseCount }} 条</el-tag>
      </div>
      <el-table v-if="regressionReport.failedCases?.length" :data="regressionReport.failedCases" size="small" border>
        <el-table-column prop="id" label="样本" width="150" />
        <el-table-column prop="question" label="问题" min-width="220" show-overflow-tooltip />
        <el-table-column prop="source" label="回答来源" width="120" />
        <el-table-column prop="reply" label="实际回答" min-width="300" show-overflow-tooltip />
        <el-table-column prop="error" label="错误" min-width="180" show-overflow-tooltip />
      </el-table>
    </div>
    <el-table
      v-show="activeStage === 'problems'"
      v-loading="loading"
      :data="questions"
      class="question-table"
      row-key="id"
    >
      <el-table-column type="expand" width="46">
        <template #default="{ row }">
          <div class="question-detail">
            <div>
              <strong>为什么被收集</strong>
              <div class="detail-tags">
                <el-tag
                  v-for="type in badCaseTypes(row.triggerTypes)"
                  :key="type"
                  size="small"
                  :type="badCaseTagType(type)"
                >{{ badCaseTypeLabel(type) }}</el-tag>
              </div>
              <p>
                {{ row.lastSource || '未记录回答来源' }}
                <span v-if="row.lastConfidence != null"> · 置信度 {{ formatScore(row.lastConfidence) }}</span>
                <span v-if="row.lastLatencyMs != null"> · 用时 {{ row.lastLatencyMs }}ms</span>
                <span v-if="row.lastCsatScore != null"> · 用户评分 {{ row.lastCsatScore }} 星</span>
              </p>
            </div>
            <div>
              <strong>建议怎么改</strong>
              <p v-if="!row.improvementAdvice?.length">先确认系统的处理是否合适，再决定是否补充知识。</p>
              <p v-for="advice in row.improvementAdvice || []" :key="advice.triggerType">
                <span class="advice-title">{{ advice.title }}：</span>{{ advice.suggestion }}
              </p>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="客户问题" min-width="360">
        <template #default="{ row }">
          <div class="question-main">{{ row.question }}</div>
          <div class="question-meta">
            <span>出现 {{ row.similarCount || 1 }} 次</span>
            <span>最近 {{ formatDateTime(row.updateTime || row.createTime) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="系统当时的处理" min-width="170">
        <template #default="{ row }">
          <div class="decision-text">{{ decisionLabel(row.lastAnswerDecision) }}</div>
            <small class="muted-text">{{ reasonCodeLabel(row.lastReasonCode, row.lastSource) }}</small>
        </template>
      </el-table-column>
      <el-table-column label="确认结果" width="140">
        <template #default="{ row }">
          <template v-if="row.reviewStatus === 'REVIEWED'">
            <el-tag size="small" :type="row.reviewCorrect === 1 ? 'success' : 'danger'">
              {{ row.reviewCorrect === 1 ? '处理合适' : '需要改进' }}
            </el-tag>
            <span class="review-decision">应当：{{ decisionLabel(row.reviewDecision) }}</span>
          </template>
          <el-tag v-else size="small" type="warning">待确认</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="下一步" width="210" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button v-if="row.reviewStatus !== 'REVIEWED'" type="primary" size="small" @click="openReview(row)">确认是否合适</el-button>
            <el-button v-else text type="primary" size="small" @click="openReview(row)">修改确认</el-button>
            <el-button v-if="row.isResolved !== 1" text size="small" @click="resolve(row.id)">完成处理</el-button>
            <span v-else class="resolved-label">已完成</span>
          </div>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty :description="reviewOnly ? '太好了，暂无待确认问题' : '还没有收集到问题'" :image-size="72">
          <el-button v-if="reviewOnly" @click="changeQuestionScope('all')">查看全部问题</el-button>
        </el-empty>
      </template>
    </el-table>
    <div v-show="activeStage === 'problems'" class="list-footer">
      <span>确认完问题后，把相似问法整理成一条 FAQ。</span>
      <el-button type="primary" plain @click="activeStage = 'organize'">下一步：补充知识</el-button>
    </div>
    <div v-show="activeStage === 'problems'" class="pagination-wrap">
      <el-pagination
        v-model:current-page="page"
        :page-size="pageSize"
        :total="total"
        layout="total, prev, pager, next"
        @current-change="fetch"
      />
    </div>
  </el-card>

  <el-card v-if="clusterResult" v-show="activeStage === 'organize'" class="cluster-card">
    <template #header>
      <div class="cluster-summary">
        <span>第二步：整理相似问题，补充 FAQ</span>
        <span class="summary-items">
          <el-tag size="small">问题 {{ clusterResult.questionCount }}</el-tag>
          <el-tag size="small" type="success">相似分组 {{ clusterResult.clusterCount }}</el-tag>
          <el-tag size="small" type="info">零散 {{ clusterResult.noiseCount }}</el-tag>
          <el-tag v-if="clusterResult.questionCount === 0" size="small" type="info">暂无待分析问题</el-tag>
          <el-tag v-if="clusterResult.questionCount > 0 && !clusterResult.embeddingUsed" size="small" type="warning">请人工核对分组</el-tag>
          <el-button size="small" plain :loading="clustering" @click="runClustering">重新整理</el-button>
          <el-button size="small" plain @click="toggleClusterEditMode">
            {{ clusterEditMode ? '完成调整' : '调整分组' }}
          </el-button>
          <el-button
            v-if="clusterEditMode"
            size="small"
            type="primary"
            :disabled="selectedClusterIds.length < 2"
            @click="mergeSelectedClusters"
          >合并所选分组</el-button>
        </span>
      </div>
    </template>
    <el-alert
      v-if="clusterResult.questionCount > 0 && !clusterResult.embeddingUsed"
      title="相似问题已按文字内容初步整理，请核对分组是否准确。"
      type="warning"
      :closable="false"
      show-icon
      class="cluster-alert"
    />
    <el-empty
      v-if="clusterResult.clusters.length === 0"
      :description="clusterResult.questionCount === 0 ? '当前没有待整理的问题' : '暂未发现可以合并的相似问题'"
    />
    <el-collapse v-else>
      <el-collapse-item v-for="cluster in clusterResult.clusters" :key="cluster.id" :name="cluster.id">
        <template #title>
          <div class="cluster-title">
            <el-checkbox
              v-if="clusterEditMode && cluster.ignored !== 1"
              :model-value="selectedClusterIds.includes(cluster.id)"
              @click.stop
              @change="toggleClusterSelection(cluster.id, $event)"
            />
            <span class="cluster-name" :class="{ 'is-ignored': cluster.ignored === 1 }">{{ cluster.title }}</span>
            <span class="cluster-meta">
              {{ cluster.questionCount }} 种问法 · 共出现 {{ cluster.totalOccurrences }} 次
            </span>
            <span class="cluster-actions" @click.stop>
              <el-tag v-if="cluster.ignored === 1" size="small" type="info">已忽略</el-tag>
              <el-tag
                v-if="draftFor(cluster.id)"
                size="small"
                :type="draftStatusType(draftFor(cluster.id).status)"
              >{{ draftStatusLabel(draftFor(cluster.id).status) }}</el-tag>
              <el-button
                v-if="cluster.ignored !== 1"
                size="small"
                type="primary"
                :loading="generatingDraftId === cluster.id"
                @click="openFaqDraft(cluster)"
              >{{ draftFor(cluster.id) ? '查看FAQ草稿' : '生成FAQ草稿' }}</el-button>
              <el-button
                v-if="clusterEditMode && cluster.ignored !== 1 && splitSelectionFor(cluster).length > 0 && splitSelectionFor(cluster).length < cluster.questions.length"
                size="small"
                text
                type="primary"
                @click="splitCluster(cluster)"
              >拆分选中</el-button>
              <el-dropdown v-if="clusterEditMode && cluster.ignored !== 1" trigger="click" @command="command => handleClusterCommand(command, cluster)">
                <el-button size="small" text>更多</el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="rename">修改标题</el-dropdown-item>
                    <el-dropdown-item command="ignore">暂时忽略</el-dropdown-item>
                    <el-dropdown-item command="delete" divided>删除分组</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </span>
          </div>
        </template>
        <el-table
          :data="cluster.questions"
          size="small"
          border
          @selection-change="rows => setSplitSelection(cluster.id, rows)"
        >
          <el-table-column v-if="clusterEditMode" type="selection" width="48" :selectable="() => cluster.ignored !== 1" />
          <el-table-column prop="question" label="客户问法" min-width="320" show-overflow-tooltip />
          <el-table-column prop="similarCount" label="出现次数" width="90" align="center" />
        </el-table>
      </el-collapse-item>
    </el-collapse>
    <div v-if="clusterResult.noiseQuestions.length" class="noise-block">
      <div class="noise-title">零散问题（展示前 {{ clusterResult.noiseQuestions.length }} 条）</div>
      <el-tag v-for="item in clusterResult.noiseQuestions" :key="item.id" class="noise-tag" type="info">
        {{ item.question }}
      </el-tag>
    </div>
    <div class="list-footer cluster-footer">
      <span>FAQ 草稿会先检查知识依据，确认后才会进入知识库。</span>
      <el-button type="primary" plain @click="activeStage = 'report'">下一步：验证效果</el-button>
    </div>
  </el-card>
  <el-card v-else-if="activeStage === 'organize'" class="empty-stage">
    <el-empty description="还没有聚类结果，先点击“整理相似问题”开始" :image-size="96">
      <el-button type="primary" :loading="clustering" @click="runClustering">整理相似问题</el-button>
    </el-empty>
  </el-card>

  <el-dialog
    v-model="draftDialogVisible"
    title="FAQ草稿审核"
    width="min(820px, 94vw)"
    destroy-on-close
  >
    <template v-if="activeDraft">
      <div class="draft-status-row">
        <el-tag :type="draftStatusType(activeDraft.status)">{{ draftStatusLabel(activeDraft.status) }}</el-tag>
        <el-tag :type="activeDraft.evidenceStatus === 'SUPPORTED' ? 'success' : 'warning'">
          {{ evidenceStatusLabel(activeDraft.evidenceStatus) }}
        </el-tag>
        <el-tag v-if="activeDraft.status === 'PUBLISHED'" type="info">
          已命中 {{ activeDraft.publishedHitCount || 0 }} 次
        </el-tag>
      </div>
      <el-alert
        v-if="activeDraft.generationMessage"
        :title="activeDraft.generationMessage"
        :type="activeDraft.evidenceStatus === 'SUPPORTED' && activeDraft.answer ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="draft-alert"
      />
      <el-alert
        v-if="activeDraft.duplicateItemId"
        :title="`发现相似FAQ #${activeDraft.duplicateItemId}，相似度 ${formatScore(activeDraft.duplicateScore)}`"
        type="warning"
        :closable="false"
        show-icon
        class="draft-alert"
      />
      <el-form label-position="top">
        <el-form-item label="标准问题">
          <el-input
            v-model="draftForm.question"
            type="textarea"
            :rows="2"
            maxlength="500"
            show-word-limit
            :disabled="activeDraft.status !== 'DRAFT'"
          />
        </el-form-item>
        <el-form-item label="答案草稿">
          <el-input
            v-model="draftForm.answer"
            type="textarea"
            :rows="7"
            maxlength="20000"
            show-word-limit
            :disabled="activeDraft.status !== 'DRAFT'"
          />
        </el-form-item>
        <el-form-item label="关键词与相似问法">
          <el-input
            v-model="draftForm.keywords"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :disabled="activeDraft.status !== 'DRAFT'"
          />
        </el-form-item>
      </el-form>
      <div v-if="activeDraft.similarQuestions?.length" class="draft-section">
        <div class="draft-section-title">客户真实问法</div>
        <el-tag
          v-for="question in activeDraft.similarQuestions"
          :key="question"
          type="info"
          class="question-tag"
        >{{ question }}</el-tag>
      </div>
      <div class="draft-section">
        <div class="draft-section-title">知识依据</div>
        <el-empty v-if="!activeDraft.evidence?.length" description="没有找到可用于发布的知识依据" :image-size="64" />
        <el-table v-else :data="activeDraft.evidence" size="small" border>
          <el-table-column prop="title" label="来源" min-width="180" />
          <el-table-column prop="snippet" label="内容摘要" min-width="320" show-overflow-tooltip />
          <el-table-column label="相关度" width="90" align="center">
            <template #default="{ row }">{{ formatScore(row.score) }}</template>
          </el-table-column>
        </el-table>
      </div>
      <el-alert
        v-if="activeDraft.status === 'REJECTED' && activeDraft.reviewReason"
        :title="`拒绝原因：${activeDraft.reviewReason}`"
        type="error"
        :closable="false"
        class="draft-alert"
      />
    </template>
    <template #footer>
      <el-button @click="draftDialogVisible = false">关闭</el-button>
      <el-button
        v-if="activeDraft?.status !== 'PUBLISHED'"
        :loading="draftSaving"
        @click="regenerateDraft"
      >重新生成</el-button>
      <el-button
        v-if="activeDraft?.status === 'DRAFT'"
        type="danger"
        plain
        :disabled="draftSaving"
        @click="rejectFaqDraft"
      >拒绝</el-button>
      <el-button
        v-if="activeDraft?.status === 'DRAFT'"
        type="primary"
        plain
        :loading="draftSaving"
        @click="saveFaqDraft"
      >保存草稿</el-button>
      <el-button
        v-if="activeDraft?.status === 'DRAFT'"
        type="success"
        :loading="draftPublishing"
        :disabled="!canPublishDraft"
        @click="publishFaqDraft"
      >审核并发布</el-button>
    </template>
  </el-dialog>
  <el-dialog v-model="reviewDialogVisible" title="确认问题处理是否合适" width="480px">
    <div v-if="reviewQuestion" class="review-question">{{ reviewQuestion.question }}</div>
    <el-form label-position="top">
      <el-form-item label="系统当时的处理">
        <el-tag type="info">{{ decisionLabel(reviewQuestion?.lastAnswerDecision) }}</el-tag>
        <span class="review-hint">{{ reasonCodeLabel(reviewQuestion?.lastReasonCode, reviewQuestion?.lastSource) }}</span>
      </el-form-item>
      <el-form-item label="这个处理合适吗？" required>
        <el-radio-group v-model="reviewForm.correct">
          <el-radio-button :value="true">正确</el-radio-button>
          <el-radio-button :value="false">错误</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="如果不合适，应该怎么处理？" required>
        <el-select v-model="reviewForm.decision" placeholder="请选择处理方式" style="width:100%">
          <el-option v-for="item in reviewDecisions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="需要改进的地方">
        <el-select v-model="reviewForm.category" clearable placeholder="可选填，帮助团队统计问题" style="width:100%">
          <el-option v-for="item in reviewCategories" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
      <el-form-item label="补充说明">
        <el-input v-model="reviewForm.note" type="textarea" :rows="3" maxlength="1000" show-word-limit placeholder="例如：补充一条产品介绍 FAQ" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="reviewDialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="reviewSaving" @click="submitReview">保存确认</el-button>
    </template>
  </el-dialog>
</template>
<script setup>
import { ref, onMounted, computed } from 'vue'
import request from '../../api/index.js'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatDateTime } from '../../utils/displayText.js'
const questions = ref([])
const loading = ref(false)
const reviewOnly = ref(true)
const activeStage = ref('problems')
const workflowSteps = [
  { value: 'problems', label: '确认问题', description: '判断系统处理是否合适' },
  { value: 'organize', label: '补充知识', description: '整理相似问法并生成 FAQ' },
  { value: 'report', label: '验证效果', description: '确认改进后是否通过' }
]
const qualityLoading = ref(false)
const quality = ref({})
const total = ref(0)
const page = ref(1)
const pageSize = 10
const clustering = ref(false)
const regressionRunning = ref(false)
const regressionReport = ref(null)
const clusterResult = ref(null)
const clusterEditMode = ref(false)
const selectedClusterIds = ref([])
const splitSelections = ref({})
const faqDrafts = ref([])
const generatingDraftId = ref(null)
const draftDialogVisible = ref(false)
const activeDraft = ref(null)
const activeDraftCluster = ref(null)
const draftForm = ref({ question: '', answer: '', keywords: '' })
const draftSaving = ref(false)
const draftPublishing = ref(false)
const reviewDialogVisible = ref(false)
const reviewSaving = ref(false)
const reviewQuestion = ref(null)
const reviewForm = ref({ correct: null, decision: '', category: '', note: '' })
const faqDraftTimeout = 180000
const regressionTimeout = 1800000
const reviewDecisions = [
  { value: 'ANSWER', label: '直接回答' },
  { value: 'CLARIFY', label: '先追问' },
  { value: 'NO_ANSWER', label: '暂时无法回答' },
  { value: 'HANDOFF', label: '转人工' }
]
const reviewCategories = ['意图识别错误', '知识库缺失', '知识库过期', '检索错误', '追问错误', '应该转人工', '不应转人工', '模型编造', '其他']
const canPublishDraft = computed(() => activeDraft.value?.status === 'DRAFT'
  && activeDraft.value?.evidenceStatus === 'SUPPORTED'
  && draftForm.value.question.trim()
  && draftForm.value.answer.trim())
const latestRegression = computed(() => quality.value.regressionHistory?.[0] || null)
const pendingDraftCount = computed(() => faqDrafts.value.filter(item => item.status === 'DRAFT').length)

function workflowStepDone(step) {
  if (step === 'problems') return questions.value.length > 0 && !quality.value.pendingReviewQuestionCount
  if (step === 'organize') return Boolean(faqDrafts.value.some(item => item.status === 'PUBLISHED'))
  return Boolean(latestRegression.value?.passed)
}

async function fetch() {
  loading.value = true
  try {
    const r = await request.get('/admin/unmatched/list', {
      params: {
        page: page.value,
        size: pageSize,
        reviewStatus: reviewOnly.value ? 'PENDING' : undefined
      }
    })
    questions.value = r.data?.records || []
    total.value = r.data?.total || 0
  } catch {
    questions.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

async function toggleReviewOnly() {
  reviewOnly.value = !reviewOnly.value
  page.value = 1
  await fetch()
}

async function changeQuestionScope(scope) {
  const nextReviewOnly = scope === 'review'
  if (reviewOnly.value === nextReviewOnly) return
  reviewOnly.value = nextReviewOnly
  page.value = 1
  await fetch()
}

async function showPendingReviews() {
  activeStage.value = 'problems'
  reviewOnly.value = true
  page.value = 1
  await fetch()
  window.scrollTo({ top: 0, behavior: 'smooth' })
}

async function loadQuality() {
  qualityLoading.value = true
  try {
    const response = await request.get('/admin/unmatched/quality')
    quality.value = response.data || {}
  } catch {
    quality.value = {}
  } finally {
    qualityLoading.value = false
  }
}

async function resolve(id) {
  await request.put('/admin/unmatched/' + id + '/resolve')
  ElMessage.success('已标记')
  await Promise.all([fetch(), loadQuality()])
}

function openReview(row) {
  reviewQuestion.value = row
  reviewForm.value = {
    correct: null,
    decision: normalizeDecision(row.lastAnswerDecision) || decisionFromStatus(row.lastAnswerStatus),
    category: '',
    note: ''
  }
  reviewDialogVisible.value = true
}

async function submitReview() {
  if (!reviewQuestion.value || reviewForm.value.correct === null || !reviewForm.value.decision) {
    ElMessage.warning('请填写判断结果和正确决策')
    return
  }
  reviewSaving.value = true
  try {
    await request.put(`/admin/unmatched/${reviewQuestion.value.id}/review`, reviewForm.value)
    reviewDialogVisible.value = false
    ElMessage.success('复核已保存')
    await Promise.all([fetch(), loadQuality()])
  } finally {
    reviewSaving.value = false
  }
}

async function runClustering() {
  clustering.value = true
  try {
    const response = await request.post('/admin/unmatched/cluster/run', null, {
      params: { limit: 500, threshold: 0.82, minClusterSize: 2 }
    })
    clusterResult.value = response.data || null
    clearReviewSelections()
    await fetchFaqDrafts()
    ElMessage.success('聚类审核批次已生成')
  } finally {
    clustering.value = false
  }
}

async function runRegression() {
  if (regressionRunning.value) return
  try {
    await ElMessageBox.confirm(
      '回归验证会调用真实模型并产生API费用，评测产生的会话数据会自动回滚。',
      '运行回归验证',
      { type: 'warning', confirmButtonText: '开始验证', cancelButtonText: '取消' }
    )
    regressionRunning.value = true
    const response = await request.post('/admin/unmatched/faq-draft/regression', {}, { timeout: regressionTimeout })
    regressionReport.value = response.data || null
    await loadQuality()
    ElMessage[response.data?.passed ? 'success' : 'warning'](
      response.data?.passed ? '回归验证通过' : '回归验证发现未通过样本'
    )
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    regressionRunning.value = false
  }
}

function formatScore(value) {
  return `${Math.round(Number(value || 0) * 100)}%`
}

function percentage(value) {
  return `${Math.round(Number(value || 0) * 1000) / 10}%`
}

function signedPercentage(value) {
  const percentageValue = Math.round(Number(value || 0) * 1000) / 10
  return `${percentageValue > 0 ? '+' : ''}${percentageValue}%`
}

function badCaseTypes(value) {
  return String(value || 'NO_ANSWER').split(',').map(item => item.trim()).filter(Boolean)
}

function badCaseTypeLabel(type) {
  return {
    NO_ANSWER: '无答案',
    GUARDRAIL: '护栏触发',
    LOW_CONFIDENCE: '低置信度',
    SLOW_RESPONSE: '响应过慢',
    LOW_RATING: '低评分'
  }[type] || type
}

function decisionLabel(value) {
  return ({ ANSWER: '直接回答', ANSWER_PARTIAL: '直接回答（部分）', CLARIFY: '先追问', NO_ANSWER: '暂时无法回答', NO_KNOWLEDGE: '暂时无法回答', HANDOFF: '转人工' })[value] || '未记录'
}

function decisionTagType(value) {
  return ({ ANSWER: 'success', CLARIFY: 'warning', NO_ANSWER: 'danger', HANDOFF: 'info' })[value] || 'info'
}

function normalizeDecision(value) {
  return ({ ANSWER: 'ANSWER', ANSWER_PARTIAL: 'ANSWER', CLARIFY: 'CLARIFY', NO_ANSWER: 'NO_ANSWER', NO_KNOWLEDGE: 'NO_ANSWER', HANDOFF: 'HANDOFF' })[value] || ''
}

function decisionFromStatus(value) {
  return ({ answered: 'ANSWER', clarify: 'CLARIFY', no_answer: 'NO_ANSWER', error: 'NO_ANSWER', handoff_requested: 'HANDOFF' })[value] || ''
}

function reasonCodeLabel(reasonCode, source) {
  const labels = {
    not_needed: '系统认为可以直接回答',
    out_of_scope: '问题超出当前服务范围',
    evidence_consistency_guardrail: '回答缺少足够的知识依据',
    low_confidence: '系统对回答不够确定',
    no_knowledge: '知识库中没有找到答案',
    slow_response: '回答时间较长',
    low_rating: '用户评价较低'
  }
  if (labels[reasonCode]) return labels[reasonCode]
  if (source === 'rag_ai') return '根据知识库生成回答'
  if (source) return `回答来源：${source}`
  return '未记录具体原因'
}

function badCaseTagType(type) {
  return {
    NO_ANSWER: 'danger',
    GUARDRAIL: 'warning',
    LOW_CONFIDENCE: 'warning',
    SLOW_RESPONSE: 'info',
    LOW_RATING: 'danger'
  }[type] || 'info'
}

async function fetchLatestCluster() {
  try {
    const response = await request.get('/admin/unmatched/cluster/list')
    clusterResult.value = response.data || null
    clearReviewSelections()
    await fetchFaqDrafts()
  } catch {
    clusterResult.value = null
    faqDrafts.value = []
  }
}

async function fetchFaqDrafts() {
  const runId = clusterResult.value?.runId
  if (!runId) {
    faqDrafts.value = []
    return
  }
  const response = await request.get('/admin/unmatched/faq-draft/list', { params: { runId } })
  faqDrafts.value = response.data || []
}

function draftFor(clusterId) {
  return faqDrafts.value.find(item => item.clusterId === clusterId)
}

function draftStatusLabel(status) {
  return ({ DRAFT: '待审核', REJECTED: '已拒绝', PUBLISHED: '已发布' })[status] || status || '未知'
}

function draftStatusType(status) {
  return ({ DRAFT: 'warning', REJECTED: 'danger', PUBLISHED: 'success' })[status] || 'info'
}

function evidenceStatusLabel(status) {
  return ({ SUPPORTED: '知识依据充足', MISSING: '知识依据不足', STALE: '知识依据待更新' })[status] || '知识依据未知'
}

function setActiveDraft(draft, cluster = activeDraftCluster.value) {
  activeDraft.value = draft
  activeDraftCluster.value = cluster
  draftForm.value = {
    question: draft?.question || '',
    answer: draft?.answer || '',
    keywords: draft?.keywords || ''
  }
  draftDialogVisible.value = Boolean(draft)
}

function replaceDraft(draft) {
  const next = faqDrafts.value.filter(item => item.id !== draft.id)
  faqDrafts.value = [draft, ...next]
  setActiveDraft(draft)
}

async function openFaqDraft(cluster) {
  if (generatingDraftId.value !== null) return
  const existing = draftFor(cluster.id)
  if (existing) {
    setActiveDraft(existing, cluster)
    return
  }
  activeDraftCluster.value = cluster
  generatingDraftId.value = cluster.id
  try {
    const response = await request.post(`/admin/unmatched/cluster/${cluster.id}/faq-draft`, null, { timeout: faqDraftTimeout })
    replaceDraft(response.data)
    ElMessage.success(response.data?.answer ? 'FAQ草稿已生成' : '草稿已建立，请检查知识依据')
  } finally {
    generatingDraftId.value = null
  }
}

async function regenerateDraft() {
  const clusterId = activeDraftCluster.value?.id || activeDraft.value?.clusterId
  if (!clusterId || draftSaving.value) return
  draftSaving.value = true
  try {
    const response = await request.post(`/admin/unmatched/cluster/${clusterId}/faq-draft`, null, {
      params: { regenerate: true },
      timeout: faqDraftTimeout
    })
    replaceDraft(response.data)
    ElMessage.success(response.data?.answer ? 'FAQ草稿已重新生成' : '已重新检查知识依据')
  } finally {
    draftSaving.value = false
  }
}

async function saveFaqDraft() {
  if (!activeDraft.value || draftSaving.value) return
  if (!draftForm.value.question.trim()) {
    ElMessage.warning('标准问题不能为空')
    return
  }
  draftSaving.value = true
  try {
    const response = await request.put(`/admin/unmatched/faq-draft/${activeDraft.value.id}`, {
      question: draftForm.value.question,
      answer: draftForm.value.answer,
      keywords: draftForm.value.keywords
    })
    replaceDraft(response.data)
    ElMessage.success('草稿已保存')
  } finally {
    draftSaving.value = false
  }
}

async function rejectFaqDraft() {
  if (!activeDraft.value) return
  try {
    const { value } = await ElMessageBox.prompt('请输入拒绝原因', '拒绝FAQ草稿', {
      inputValidator: value => value && value.trim() ? true : '拒绝原因不能为空',
      confirmButtonText: '确认拒绝',
      cancelButtonText: '取消'
    })
    const response = await request.post(`/admin/unmatched/faq-draft/${activeDraft.value.id}/reject`, {
      reason: value
    })
    replaceDraft(response.data)
    ElMessage.success('FAQ草稿已拒绝')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function publishFaqDraft() {
  if (!activeDraft.value || !canPublishDraft.value || draftPublishing.value) return
  try {
    await ElMessageBox.confirm(
      '发布后该FAQ会进入知识库并参与智能客服回答。',
      '审核并发布',
      { type: 'warning', confirmButtonText: '确认发布', cancelButtonText: '取消' }
    )
    draftPublishing.value = true
    await saveFaqDraft()
    if (activeDraft.value?.evidenceStatus !== 'SUPPORTED') {
      ElMessage.warning('标准问题已修改，请重新生成并确认知识依据')
      return
    }
    const response = await request.post(`/admin/unmatched/faq-draft/${activeDraft.value.id}/publish`, null, {
      timeout: faqDraftTimeout
    })
    replaceDraft(response.data)
    regressionReport.value = null
    await fetch()
    ElMessage.success('FAQ已发布并完成向量索引')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  } finally {
    draftPublishing.value = false
  }
}

function clearReviewSelections() {
  selectedClusterIds.value = []
  splitSelections.value = {}
}

function toggleClusterEditMode() {
  clusterEditMode.value = !clusterEditMode.value
  if (!clusterEditMode.value) clearReviewSelections()
}

function handleClusterCommand(command, cluster) {
  if (command === 'rename') renameCluster(cluster)
  else if (command === 'ignore') ignoreCluster(cluster)
  else if (command === 'delete') deleteCluster(cluster)
}

function toggleClusterSelection(id, checked) {
  const selected = new Set(selectedClusterIds.value)
  if (checked) selected.add(id)
  else selected.delete(id)
  selectedClusterIds.value = [...selected]
}

function setSplitSelection(id, rows) {
  splitSelections.value[id] = rows || []
}

function splitSelectionFor(cluster) {
  return splitSelections.value[cluster.id] || []
}

async function mergeSelectedClusters() {
  const selected = selectedClusterIds.value
  if (selected.length < 2) return
  try {
    await ElMessageBox.confirm(
      '将保留第一个选中聚类的标题，并把其他选中聚类的成员复制到其中。',
      '合并聚类',
      { type: 'warning', confirmButtonText: '确认合并', cancelButtonText: '取消' }
    )
    await request.post('/admin/unmatched/cluster/merge', {
      targetId: selected[0], sourceIds: selected.slice(1)
    })
    ElMessage.success('聚类已合并')
    await fetchLatestCluster()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function splitCluster(cluster) {
  const selected = splitSelectionFor(cluster)
  if (!selected.length || selected.length >= cluster.questions.length) return
  try {
    const { value } = await ElMessageBox.prompt('可选填写新聚类标题', '拆分聚类', {
      inputValue: selected[0]?.question || '',
      confirmButtonText: '确认拆分',
      cancelButtonText: '取消'
    })
    await request.post(`/admin/unmatched/cluster/${cluster.id}/split`, {
      questionIds: selected.map(item => item.id), title: value
    })
    ElMessage.success('聚类已拆分')
    await fetchLatestCluster()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function renameCluster(cluster) {
  try {
    const { value } = await ElMessageBox.prompt('请输入聚类标题', '修改聚类标题', {
      inputValue: cluster.title,
      inputValidator: value => value && value.trim() ? true : '标题不能为空',
      confirmButtonText: '保存',
      cancelButtonText: '取消'
    })
    await request.put(`/admin/unmatched/cluster/${cluster.id}/title`, { title: value })
    cluster.title = value.trim()
    ElMessage.success('标题已更新')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function ignoreCluster(cluster) {
  try {
    await ElMessageBox.confirm('忽略后仅从审核列表中标记，不会删除原始问题。', '忽略聚类', {
      type: 'warning', confirmButtonText: '确认忽略', cancelButtonText: '取消'
    })
    await request.put(`/admin/unmatched/cluster/${cluster.id}/ignore`)
    cluster.ignored = 1
    ElMessage.success('已忽略该聚类')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

async function deleteCluster(cluster) {
  try {
    await ElMessageBox.confirm(
      '删除后只会移除当前聚类审核记录及未发布的FAQ草稿，原始未命中问题不会删除。',
      '删除聚类',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
    await request.delete(`/admin/unmatched/cluster/${cluster.id}`)
    if (activeDraftCluster.value?.id === cluster.id) {
      draftDialogVisible.value = false
      activeDraft.value = null
      activeDraftCluster.value = null
    }
    ElMessage.success('聚类已删除')
    await fetchLatestCluster()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') throw error
  }
}

onMounted(() => {
  fetch()
  fetchLatestCluster()
  loadQuality()
})
</script>
<style scoped>
.pool-intro { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; margin-bottom:14px; padding:22px 24px; border:1px solid #dbe7f5; border-radius:10px; background:linear-gradient(135deg,#f4f8ff 0%,#fff 68%); }
.intro-copy { min-width:0; }
.eyebrow { color:#3f7acb; font-size:12px; font-weight:700; letter-spacing:.08em; }
.pool-intro h1 { margin:6px 0 7px; color:#1f2d3d; font-size:22px; line-height:1.35; }
.pool-intro p { max-width:760px; margin:0; color:#667085; line-height:1.7; }
.guide-popover p { margin:8px 0 0; color:#667085; font-size:13px; line-height:1.6; }
.workflow-nav { display:grid; grid-template-columns:repeat(3,minmax(0,1fr)); gap:10px; margin-bottom:14px; }
.workflow-step { display:flex; align-items:center; gap:10px; min-width:0; padding:12px 14px; border:1px solid #e2e8f0; border-radius:8px; background:#fff; color:#667085; text-align:left; cursor:pointer; transition:all .2s ease; }
.workflow-step:hover { border-color:#9bbcf0; background:#f8fbff; }
.workflow-step.active { border-color:#409eff; box-shadow:0 0 0 2px rgba(64,158,255,.1); background:#f5f9ff; }
.workflow-step.done .step-number { background:#67c23a; }
.step-number { flex:0 0 auto; display:inline-flex; align-items:center; justify-content:center; width:26px; height:26px; border-radius:50%; background:#c7d2e3; color:#fff; font-size:13px; font-weight:700; }
.workflow-step.active .step-number { background:#409eff; }
.step-copy { display:flex; flex-direction:column; min-width:0; gap:2px; }
.step-copy strong { color:#344054; font-size:14px; }
.step-copy small { overflow:hidden; color:#98a2b3; font-size:12px; text-overflow:ellipsis; white-space:nowrap; }
.step-count { flex:0 0 auto; margin-left:auto; color:#3f7acb; font-size:12px; }
.work-card { min-height:280px; }
.section-heading { display:flex; align-items:center; justify-content:space-between; gap:16px; }
.section-heading h2 { margin:0; color:#1f2d3d; font-size:18px; }
.section-heading p { margin:5px 0 0; color:#7b8494; font-size:13px; font-weight:400; }
.question-table :deep(.el-table__expanded-cell) { padding:0; background:#f8fafc; }
.question-main { color:#1f2d3d; font-size:14px; line-height:1.5; }
.question-meta { display:flex; flex-wrap:wrap; gap:12px; margin-top:6px; color:#98a2b3; font-size:12px; }
.decision-text { color:#344054; font-size:13px; }
.muted-text { color:#98a2b3; }
.question-detail { display:grid; grid-template-columns:minmax(220px,.8fr) minmax(300px,1.2fr); gap:28px; padding:16px 42px 16px 64px; }
.question-detail strong { color:#475467; font-size:13px; }
.question-detail p { margin:8px 0 0; color:#667085; font-size:13px; line-height:1.65; }
.detail-tags { display:flex; flex-wrap:wrap; gap:6px; margin-top:9px; }
.row-actions { display:flex; align-items:center; flex-wrap:wrap; gap:3px; }
.resolved-label { color:#67c23a; font-size:12px; }
.list-footer { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-top:14px; padding:13px 16px; border:1px solid #e8eef6; border-radius:6px; background:#fafcff; color:#667085; font-size:13px; }
.empty-stage { min-height:260px; }
.cluster-footer { margin-top:16px; }
.header-actions { display:flex; align-items:center; justify-content:flex-end; flex-wrap:wrap; gap:10px; }
.quality-overview { margin-bottom:16px; padding:16px 0 0; border-bottom:1px solid #ebeef5; }
.review-entry { margin:14px 0 16px; }
.review-entry :deep(.el-alert__title) { display:flex; align-items:center; gap:12px; flex-wrap:wrap; }
.review-entry-button { margin-left:4px; }
.quality-metrics { display:grid; grid-template-columns:repeat(6, minmax(130px, 1fr)); margin:0; border:1px solid #ebeef5; border-radius:6px; }
.quality-metrics div { min-width:0; padding:14px 16px; border-right:1px solid #ebeef5; }
.quality-metrics div:last-child { border-right:0; }
.quality-metrics dt { color:#7b8494; font-size:13px; }
.quality-metrics dd { margin:6px 0 0; color:#202938; font-size:24px; font-weight:700; font-variant-numeric:tabular-nums; }
.quality-details { display:grid; grid-template-columns:minmax(220px, .7fr) minmax(420px, 1.3fr); gap:24px; margin-top:16px; }
.decision-distribution { margin-top:16px; padding-top:14px; border-top:1px solid #ebeef5; }
.decision-list { display:flex; flex-wrap:wrap; gap:8px 20px; }
.decision-row { display:flex; align-items:center; justify-content:space-between; gap:12px; min-width:150px; color:#606266; font-size:13px; }
.quality-block { min-width:0; }
.quality-block-heading { display:flex; align-items:center; justify-content:space-between; gap:10px; min-height:28px; margin-bottom:8px; }
.quality-block-heading strong { color:#303133; }
.quality-block-heading span { color:#909399; font-size:12px; }
.trigger-list { border-top:1px solid #ebeef5; }
.trigger-row { display:flex; align-items:center; justify-content:space-between; min-height:36px; border-bottom:1px solid #ebeef5; color:#606266; font-size:13px; }
.rate-up { color:#198754 !important; }
.rate-down { color:#c43d3d !important; }
.repeated-failures { margin-top:16px; padding-top:14px; border-top:1px solid #ebeef5; }
.repeated-list { display:grid; grid-template-columns:repeat(2, minmax(0, 1fr)); gap:8px 16px; }
.repeated-item { display:flex; align-items:center; justify-content:space-between; gap:10px; min-width:0; padding:8px 0; border-bottom:1px solid #f0f2f5; }
.repeated-question { overflow:hidden; color:#606266; text-overflow:ellipsis; white-space:nowrap; }
.reason-tag { margin:2px 6px 2px 0; }
.advice-item + .advice-item { margin-top:6px; }
.advice-title { color:#303133; font-weight:600; }
.review-decision { display:block; margin-top:3px; color:#7b8494; font-size:12px; }
.review-question { padding:10px 12px; margin-bottom:18px; color:#303133; line-height:1.6; background:#f5f7fa; border-radius:4px; }
.review-hint { margin-left:10px; color:#909399; font-size:12px; }
.regression-result { margin-bottom:16px; }
.regression-metrics { display:flex; flex-wrap:wrap; gap:8px; margin:10px 0; }
.pagination-wrap { display:flex; justify-content:flex-end; margin-top:16px; overflow-x:auto; }
.cluster-card { margin-top:16px; }
.cluster-summary { display:flex; justify-content:space-between; align-items:center; gap:12px; }
.summary-items { display:flex; flex-wrap:wrap; gap:8px; justify-content:flex-end; }
.cluster-alert { margin-bottom:12px; }
.cluster-title { display:flex; align-items:center; flex-wrap:wrap; gap:12px; min-width:0; width:100%; padding-right:12px; }
.cluster-name { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.cluster-meta { color:#8492a6; font-size:12px; white-space:nowrap; }
.cluster-actions { margin-left:auto; display:flex; flex-wrap:wrap; justify-content:flex-end; gap:6px; }
.is-ignored { color:#909399; }
.noise-block { margin-top:18px; padding-top:14px; border-top:1px solid #ebeef5; }
.noise-title { color:#606266; font-size:13px; margin-bottom:10px; }
.noise-tag { margin:0 8px 8px 0; max-width:100%; }
.draft-status-row { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:12px; }
.draft-alert { margin-bottom:12px; }
.draft-section { margin-top:18px; }
.draft-section-title { color:#303133; font-weight:600; margin-bottom:10px; }
.question-tag { margin:0 8px 8px 0; max-width:100%; white-space:normal; height:auto; }
@media (max-width: 720px) {
  .pool-intro { padding:18px; }
  .pool-intro h1 { font-size:19px; }
  .workflow-nav { grid-template-columns:1fr; }
  .section-heading { align-items:flex-start; flex-direction:column; }
  .question-detail { grid-template-columns:1fr; gap:12px; padding:14px 20px; }
  .list-footer { align-items:flex-start; flex-direction:column; }
  .quality-metrics { grid-template-columns:repeat(2, minmax(0, 1fr)); }
  .quality-metrics div:nth-child(even) { border-right:0; }
  .quality-metrics div:nth-child(-n+4) { border-bottom:1px solid #ebeef5; }
  .quality-details, .repeated-list { grid-template-columns:minmax(0, 1fr); }
  .header-actions, .cluster-summary { align-items:flex-start; flex-direction:column; }
  .summary-items { justify-content:flex-start; }
  .cluster-title { display:block; }
  .cluster-meta { display:block; margin-top:4px; }
  .cluster-actions { margin-top:6px; margin-left:0; }
}
</style>
