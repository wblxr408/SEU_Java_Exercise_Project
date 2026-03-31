/**
 * 数据生成器模块 - 生成测试数据
 */

const EMOTION_POSTS = [
  '今天天气真好，心情很开心！感恩生活中的每一天，希望未来更美好。',
  '项目终于上线了，所有人的努力都没有白费，太激动了！',
  '今天加班到很晚，但是解决了困扰我一周的bug，超有成就感！',
  '收到了一份意外的礼物，朋友真的很用心，感动到想哭。',
  '今天考试终于结束了，不管结果如何，终于可以好好休息一下了。',
  '和家人一起吃火锅，聊聊最近的生活，这种平凡的幸福最珍贵。',
  '今天的夕阳特别美，大自然的色彩让人心旷神怡。',
  '读完了一本好书，书中的一句话让我思考了很久。',
  '终于鼓起勇气做出了决定，虽然不容易但相信是对的。',
  '好久不见的老朋友突然联系，发现大家都没变，太开心了！',
];

const EMOTION_COMMENTS = [
  '很棒的分享！',
  '说得太对了，支持你！',
  '这让我想起了一些事……',
  '确实是这样，感同身受',
  '加油，一切都会好起来的！',
  '哈哈哈，太有意思了',
  '这个观点很有新意',
  '我也有类似的经历',
  '值得深思',
  '说得太好了，给你点赞！',
  '哈哈笑死我了',
  '这也太真实了吧',
  '收藏了，感谢分享',
  '希望能帮到更多人',
  '完全同意你的看法',
];

/**
 * 随机选择数组元素
 */
function randomChoice(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

/**
 * 生成随机帖子内容
 * @param {number} seed - 用于伪随机一致性（同一 seed 同一内容）
 */
export function randomPostContent(seed = 0) {
  if (seed > 0) {
    return EMOTION_POSTS[seed % EMOTION_POSTS.length];
  }
  return randomChoice(EMOTION_POSTS);
}

/**
 * 生成随机评论内容
 * @param {number} seed - 用于伪随机一致性
 */
export function randomCommentContent(seed = 0) {
  if (seed > 0) {
    return EMOTION_COMMENTS[seed % EMOTION_COMMENTS.length];
  }
  return randomChoice(EMOTION_COMMENTS);
}

/**
 * 生成随机昵称
 */
export function randomNickname(vuId, iter) {
  const prefixes = ['开心', '阳光', '微笑', '星辰', '海风', '云朵', '竹林', '枫叶', '雪花', '晨曦'];
  const suffixes = ['男孩', '女孩', '侠客', '使者', '达人', '小子', '公主', '骑士', '精灵', '天使'];
  const prefix = prefixes[(vuId + iter) % prefixes.length];
  const suffix = suffixes[(iter * 3 + vuId * 7) % suffixes.length];
  return `${prefix}${suffix}`;
}

/**
 * 生成随机邮箱
 */
export function randomEmail(username) {
  return `${username}@k6.test`;
}

/**
 * 生成情感标签（用于查询过滤）
 */
export function randomEmotionLabel() {
  const labels = ['POSITIVE', 'NEGATIVE', 'NEUTRAL', null];
  return randomChoice(labels);
}
