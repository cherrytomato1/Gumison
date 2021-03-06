package com.ssafy.gumison.redis;

import com.ssafy.gumison.common.dto.UserExpTierDto;
import com.ssafy.gumison.common.dto.UserRankDto;
import com.ssafy.gumison.common.enums.RedisKey;
import com.ssafy.gumison.common.exception.ResourceNotFoundException;
import com.ssafy.gumison.db.entity.User;
import com.ssafy.gumison.db.repository.UserRepository;
import com.ssafy.gumison.db.repository.UserRepositorySupport;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

/**
 * 레디스 ZSet(Sorted Set)을 제어하기 위한 인터페이스의 구현체.
 *
 * @author cherrytomato1
 * @version 1.7   loadAllUser
 */
@Component
@Slf4j
public class RankProviderImpl implements RankProvider {

  private final Long MAX_EXP = 0x1_FFFF_FFFFL;

  private final Long TIER_BASE_SCORE = 0x3DFD240L;

  private final String KEY_PREFIX = RedisKey.GUMISON_CACHE.name();

  private final UserRepositorySupport userRepositorySupport;

  private final UserRepository userRepository;

  private final ZSetOperations<String, Object> zSetOperations;

  private Long userCount;

  public RankProviderImpl(UserRepositorySupport userRepositorySupport,
      RedisTemplate<String, Object> redisTemplate, UserRepository userRepository) {
    this.userRepositorySupport = userRepositorySupport;
    this.userRepository = userRepository;
    this.zSetOperations = redisTemplate.opsForZSet();
    loadAllUserExpIntoRankZSet();
  }

  /**
   * 모든 유저의 닉네임과 경험치를 ZSet에 입력.
   *
   * @return 입력된 유저의 수
   */
  @Override
  public Long loadAllUserExpIntoRankZSet() {

    List<UserExpTierDto> userExpTierDtoList = userRepositorySupport.findNicknamesAndExpAll();

    Set<TypedTuple<Object>> userExpTierTupleSet = new HashSet<>();

    log.info("Load all user exp into ZSet, size - {}", userExpTierDtoList.size());
    userExpTierDtoList.forEach(userExpTierDto -> {
      String nickname = userExpTierDto.getNickname();
      Long accumulateExp = userExpTierDto.getAccumulateExp();
      Long tierCode = userExpTierDto.getTierCode();

      if (nickname == null || accumulateExp == null || tierCode == null) {
        log.error("illegal user information, user nickname - {}, user Exp - {}, tier code - {}",
            nickname,
            accumulateExp, tierCode);
        return;
      }

      double score = (double) getScoreByExpAndTierCode(accumulateExp, tierCode);

      userExpTierTupleSet.add(new DefaultTypedTuple<>(nickname, score));
//      zSetOperations
//          .add(KEY_PREFIX + RedisKey.RANK.name(), nickname, score);
    });

    removeAllUsersFromZSet();

    Optional<Long> loadedUserCount = Optional.empty();
    while (!loadedUserCount.isPresent()) {
      loadedUserCount = Optional
          .ofNullable(
              zSetOperations.addIfAbsent(KEY_PREFIX + RedisKey.RANK.name(), userExpTierTupleSet));
    }

    log.info("[rank provider] load all user into ZSet, User Count - {}", loadedUserCount.get());

    this.userCount = loadedUserCount.get();
    return userCount;
  }

  /**
   * 닉네임으로 해당 유저의 순위 가져오기.
   *
   * @param nickname 순위를 가져올 유저의 닉네임
   * @return 유저 닉네임, 순위가 기록된 DTO
   */
  @Override
  public UserRankDto getUserRankByNickname(String nickname) {

    Optional<Long> userRankOptional = Optional
        .ofNullable(zSetOperations.rank(KEY_PREFIX + RedisKey.RANK, nickname));
    log.info("load user rank, nickname - {}, rank - {}", nickname, userRankOptional.orElse(-1L));

    if (!userRankOptional.isPresent()) {
      User currUser = userRepository.findByNickname(nickname)
          .orElseThrow(() -> new ResourceNotFoundException("User", nickname, "nickname"));

      zSetOperations.addIfAbsent(KEY_PREFIX + RedisKey.RANK, nickname, getScoreByExpAndTierCode(
          currUser.getAccumulateExp(), currUser.getTierCode()));
      userCount++;

      userRankOptional = Optional
          .ofNullable(zSetOperations.rank(KEY_PREFIX + RedisKey.RANK, nickname));
    }
    return UserRankDto
        .of(nickname, userRankOptional
            .orElseThrow(() -> new ResourceNotFoundException("User - ZSet", nickname, "nickname"))
            + 1);
  }

  /**
   * 시작 오프셋 + limit 의 유저 랭크 정보 및 닉네임 반환.
   *
   * @param startOffset 시작하는 사용자 위치 인덱스
   * @param limit       가져올 사용자 수
   * @return 유저 닉네임, 랭크 순위 리스트 (size() == limit)
   */
  @Override
  public List<UserRankDto> getUserRankByStartOffsetAndLimit(long startOffset, int limit) {

    long endOffset = startOffset + limit;
    Optional<Set<Object>> setOptional = Optional.ofNullable(zSetOperations
        .range(KEY_PREFIX + RedisKey.RANK, startOffset, endOffset < userCount ? endOffset : -1));

    if (!setOptional.isPresent()) {
      throw new RuntimeException("redis is used in pipeline or transaction");
    }

    List<UserRankDto> userRankDtoList = new ArrayList<>(limit);
    AtomicLong rank = new AtomicLong(startOffset + 1);
    log.info("load user rank start offset - {}, limit - {}, rank default - {}", startOffset, limit,
        rank);
    setOptional.get()
        .forEach(v -> userRankDtoList.add(UserRankDto.of((String) v, rank.getAndIncrement())));

    return userRankDtoList;
  }

  /**
   * ZSet에 저장된 유저 수를 반환.
   *
   * @return ZSet에 저장된 유저 수
   */
  @Override
  public Long getUserCount() {
    this.userCount = zSetOperations.zCard(KEY_PREFIX + RedisKey.RANK);
    return userCount;
  }

  /**
   * 해당하는 사용자 닉네임 Value를 ZSet에서 삭제.
   *
   * @param nickname 사용자 닉네임
   * @return 삭제 성공 여부, 존재하지 않는 유저일 경우 false
   */
  @Override
  public boolean removeUserByNickname(String nickname) {
    log.info("[rank-provider] delete user nickname - {}", nickname);
    userCount--;
    return zSetOperations.remove(KEY_PREFIX + RedisKey.RANK, nickname) != null;
  }

  /**
   * ZSet의 모든 사용자 제거
   *
   * @return 제거된 사용자 수
   */
  @Override
  public Long removeAllUsersFromZSet() {
    Long removedUserCount = zSetOperations.removeRange(KEY_PREFIX + RedisKey.RANK, 0, -1);
    log.info("[rank provider] remove all user from ZSet, user count - {}", removedUserCount);
    return removedUserCount;
  }

  private long getScoreByExpAndTierCode(Long exp, Long tierCode) {
    return MAX_EXP - ((tierCode % 200) * TIER_BASE_SCORE + exp);
  }
}
