package cn.hkxj.platform.service;

import cn.hkxj.platform.dao.*;
import cn.hkxj.platform.pojo.*;
import cn.hkxj.platform.pojo.vo.CourseTimeTableDetailVo;
import cn.hkxj.platform.spider.newmodel.coursetimetable.TimeAndPlace;
import cn.hkxj.platform.spider.newmodel.coursetimetable.UrpCourseTimeTable;
import cn.hkxj.platform.spider.newmodel.coursetimetable.UrpCourseTimeTableForSpider;
import cn.hkxj.platform.utils.DateUtils;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * @author Yuki
 * @date 2019/8/29 21:40
 */
@Slf4j
@Service
public class CourseTimeTableService {

    private static final String NO_COURSE_TEXT = "今天没有课呐，可以出去浪了~\n";

    private static ThreadFactory courseTimeTableThreadFactory = new ThreadFactoryBuilder().setNameFormat("courseTimeTable-pool-%d").build();

    private static ExecutorService dbExecutorPool = new ThreadPoolExecutor(1, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(20), courseTimeTableThreadFactory);

    @Resource
    private RoomService roomService;
    @Resource
    private RoomDao roomDao;
    @Resource
    private PlanDao planDao;
    @Resource
    private StudentDao studentDao;
    @Resource
    private UrpCourseService urpCourseService;
    @Resource
    private CourseTimeTableDetailDao courseTimeTableDetailDao;
    @Resource
    private CourseTimeTableBasicInfoDao courseTimeTableBasicInfoDao;
    @Resource
    private NewUrpSpiderService newUrpSpiderService;

    /**
     * 这个方法只能将一天的数据转换成当日课表所需要的文本
     * @param details 当天的课程时间表详情
     * @return 课表
     */
    public String convertToText(List<CourseTimeTableDetail> details) {
        if (CollectionUtils.isEmpty(details)) {return NO_COURSE_TEXT;}
        StringBuilder builder = new StringBuilder();
        details.sort(Comparator.comparing(CourseTimeTableDetail::getOrder));
        int count = 0;
        int length = details.size();
        for (CourseTimeTableDetail detail : details) {
            if (detail == null) {continue;}
            count++;
            builder.append(computationOfKnots(detail)).append("节").append("\n")
                    .append(urpCourseService.getUrpCourseByCourseId(detail.getCourseId()).getCourseName()).append("\n")
                    .append(detail.getRoomName());
            if (count != length) {
                builder.append("\n\n");
            }
        }
        return builder.toString();
    }

    public List<CourseTimeTableDetailVo> getAllCourseTimeTableDetailVos(String account){
        Student student = studentDao.selectStudentByAccount(Integer.parseInt(account));
        List<CourseTimeTableDetail> details = getAllCourseTimeTableDetails(student);
        return details.stream().map(detail -> {
            CourseTimeTableDetailVo detailVo = new CourseTimeTableDetailVo();
            detailVo.setDetail(detail);
            detailVo.setUrpCourse(urpCourseService.getUrpCourseByCourseId(detail.getCourseId()));
            return detailVo;
        }).collect(Collectors.toList());
    }

    /**
     * 获取当前学期所有的课程时间表
     * @param student 学生实体
     * @return 课程时间表详情
     */
    public List<CourseTimeTableDetail> getAllCourseTimeTableDetails(Student student){
        SchoolTime schoolTime = DateUtils.getCurrentSchoolTime();
        List<CourseTimeTableDetail> dbResult =
                courseTimeTableDetailDao.getCourseTimeTableDetailForCurrentTerm(student.getClasses().getId(), schoolTime);
        if(CollectionUtils.isEmpty(dbResult)){
            UrpCourseTimeTableForSpider spiderResult = newUrpSpiderService.getUrpCourseTimeTable(student);
            dbResult = getCurrentTermDataFromSpider(spiderResult, schoolTime);
            dbExecutorPool.execute(() -> saveCourseTimeTableToDb(spiderResult, student));
        }
        return dbResult;
    }

    /**
     * 获取当前学期当前周所有的课程时间表
     * @param student 学生实体
     * @return 课程时间表详情
     */
    public List<CourseTimeTableDetail> getDetailsForCurrentWeek(Student student) {
        SchoolTime schoolTime = DateUtils.getCurrentSchoolTime();
        List<CourseTimeTableDetail> dbResult =
                courseTimeTableDetailDao.getCourseTimeTableDetailForCurrentWeek(student.getClasses().getId(), schoolTime);
        if (CollectionUtils.isEmpty(dbResult)) {
            UrpCourseTimeTableForSpider spiderResult = newUrpSpiderService.getUrpCourseTimeTable(student);
            dbExecutorPool.execute(() -> saveCourseTimeTableToDb(spiderResult, student));
            dbResult = getCurrentWeekDataFromSpider(spiderResult, schoolTime);
        }
        return dbResult;
    }

    /**
     * 返回该学期当天的所有课程详情
     * @param student 学生实体
     * @return 课程时间表详情
     */
    public List<CourseTimeTableDetail> getDetailsForCurrentDay(Student student) {
        SchoolTime schoolTime = DateUtils.getCurrentSchoolTime();
        List<CourseTimeTableDetail> searchResult =
                courseTimeTableDetailDao.getCourseTimeTableDetailForCurrentDay(student.getClasses().getId(), schoolTime);
        if (CollectionUtils.isEmpty(searchResult)) {
            UrpCourseTimeTableForSpider spiderResult = newUrpSpiderService.getUrpCourseTimeTable(student);
            searchResult = getCurrentDayDataFromSpider(spiderResult, schoolTime);
            dbExecutorPool.execute(() -> saveCourseTimeTableToDb(spiderResult, student));
        }
        return searchResult;
    }

    /**
     * 将爬虫返回的数据中用于显示的部分全部提取出来，提取的部分为当前学期的所有课程时间表
     * @param spiderResult 爬虫返回的结果
     * @param schoolTime 当前学期等信息
     * @return 课程时间表详情
     */
    private List<CourseTimeTableDetail> getCurrentTermDataFromSpider(UrpCourseTimeTableForSpider spiderResult, SchoolTime schoolTime){
        List<CourseTimeTableDetail> result = Lists.newArrayListWithCapacity(16);
        for (Map<String, UrpCourseTimeTable> map : spiderResult.getDetails()) {
            for (Map.Entry<String, UrpCourseTimeTable> entry : map.entrySet()) {
                UrpCourseTimeTable urpCourseTimeTable = entry.getValue();
                if(CollectionUtils.isEmpty(urpCourseTimeTable.getTimeAndPlaceList())) {continue;}
                for (int i = 0, length = urpCourseTimeTable.getTimeAndPlaceList().size(); i < length; i++) {
                    TimeAndPlace timeAndPlace = urpCourseTimeTable.getTimeAndPlaceList().get(i);
                    //TimeAndPlace保存了上课的地点和时间，因为周数有时候存在多个的情况，如1-5, 8-16周等情况
                    //所以TimeAndPlace转换的CourseTimeTableDetail返回的是一个集合
                    List<CourseTimeTableDetail> details =
                            timeAndPlace.convertToCourseTimeTableDetail(urpCourseTimeTable.getCourseRelativeInfo(), urpCourseTimeTable.getAttendClassTeacher());
                    details.stream().filter(detail -> Objects.equals(detail.getTermYear(), schoolTime.getTerm().getTermYear()))
                            .filter(detail -> detail.getTermOrder() == schoolTime.getTerm().getOrder())
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    /**
     * 将爬虫返回的数据中用于显示的部分全部提取出来，提取的部分为当前学期当前周的所有课程时间表
     * @param spiderResult 爬虫返回的结果
     * @param schoolTime 当前学期等信息
     * @return 课程时间表详情
     */
    private List<CourseTimeTableDetail> getCurrentWeekDataFromSpider(UrpCourseTimeTableForSpider spiderResult, SchoolTime schoolTime) {
        List<CourseTimeTableDetail> result = Lists.newArrayListWithCapacity(16);
        for (Map<String, UrpCourseTimeTable> map : spiderResult.getDetails()) {
            for (Map.Entry<String, UrpCourseTimeTable> entry : map.entrySet()) {
                UrpCourseTimeTable urpCourseTimeTable = entry.getValue();
                if(CollectionUtils.isEmpty(urpCourseTimeTable.getTimeAndPlaceList())) {continue;}
                for (int i = 0, length = urpCourseTimeTable.getTimeAndPlaceList().size(); i < length; i++) {
                    TimeAndPlace timeAndPlace = urpCourseTimeTable.getTimeAndPlaceList().get(i);
                    //TimeAndPlace保存了上课的地点和时间，因为周数有时候存在多个的情况，如1-5, 8-16周等情况
                    //所以TimeAndPlace转换的CourseTimeTableDetail返回的是一个集合
                    List<CourseTimeTableDetail> details =
                            timeAndPlace.convertToCourseTimeTableDetail(urpCourseTimeTable.getCourseRelativeInfo(), urpCourseTimeTable.getAttendClassTeacher());
                    details.stream().filter(detail -> detail.isActiveWeek(schoolTime.getWeek()))
                            .filter(detail -> Objects.equals(detail.getTermYear(), schoolTime.getTerm().getTermYear()))
                            .filter(detail -> detail.getTermOrder() == schoolTime.getTerm().getOrder())
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    /**
     * 将爬虫返回的数据中用于显示的部分全部提取出来，提取的部分为当前学期当前周当天的所有课程时间表
     * @param spiderResult 爬虫返回的结果
     * @param schoolTime 当前学期等信息
     * @return 课程时间表详情
     */
    private List<CourseTimeTableDetail> getCurrentDayDataFromSpider(UrpCourseTimeTableForSpider spiderResult, SchoolTime schoolTime) {
        List<CourseTimeTableDetail> result = Lists.newArrayListWithCapacity(4);
        for (Map<String, UrpCourseTimeTable> map : spiderResult.getDetails()) {
            for (Map.Entry<String, UrpCourseTimeTable> entry : map.entrySet()) {
                UrpCourseTimeTable urpCourseTimeTable = entry.getValue();
                if (CollectionUtils.isEmpty(urpCourseTimeTable.getTimeAndPlaceList())) {continue;}
                for (int i = 0, length = urpCourseTimeTable.getTimeAndPlaceList().size(); i < length; i++) {
                    TimeAndPlace timeAndPlace = urpCourseTimeTable.getTimeAndPlaceList().get(i);
                    //TimeAndPlace保存了上课的地点和时间，因为周数有时候存在多个的情况，如1-5, 8-16周等情况
                    //所以TimeAndPlace转换的CourseTimeTableDetail返回的是一个集合
                    List<CourseTimeTableDetail> details =
                            timeAndPlace.convertToCourseTimeTableDetail(urpCourseTimeTable.getCourseRelativeInfo(), urpCourseTimeTable.getAttendClassTeacher());
                    details.stream().filter(detail -> detail.getDay() == schoolTime.getDay())
                            .filter(detail -> detail.isActiveWeek(schoolTime.getWeek()))
                            .filter(detail -> Objects.equals(detail.getTermYear(), schoolTime.getTerm().getTermYear()))
                            .filter(detail -> detail.getTermOrder() == schoolTime.getTerm().getOrder())
                            .forEach(result::add);
                }
            }
        }
        return result;
    }

    private void saveCourseTimeTableToDb(UrpCourseTimeTableForSpider spiderResult, Student student) {
        for (Map<String, UrpCourseTimeTable> map : spiderResult.getDetails()) {
            for (Map.Entry<String, UrpCourseTimeTable> entry : map.entrySet()) {
                UrpCourseTimeTable urpCourseTimeTable = entry.getValue();
                String courseId = urpCourseTimeTable.getCourseRelativeInfo().getCourseNumber();
                //查看课程是否存在，不存在就插入数据库
                urpCourseService.checkOrSaveUrpCourseToDb(courseId, student);
                CourseTimeTableBasicInfo basicInfo = getCourseTimeTableBasicInfo(urpCourseTimeTable);
                //将课程时间表存储到数据库
                saveCourseTimeTableDetailsToDb(urpCourseTimeTable, basicInfo, student);
            }
        }
    }

    private void saveCourseTimeTableDetailsToDb(UrpCourseTimeTable urpCourseTimeTable, CourseTimeTableBasicInfo basicInfo, Student student){
        if(CollectionUtils.isEmpty(urpCourseTimeTable.getTimeAndPlaceList())) {return;}
        for (int i = 0, length = urpCourseTimeTable.getTimeAndPlaceList().size(); i < length; i++) {
            TimeAndPlace timeAndPlace = urpCourseTimeTable.getTimeAndPlaceList().get(i);
            //TimeAndPlace保存了上课的地点和时间，因为周数有时候存在多个的情况，如1-5, 8-16周等情况
            //所以TimeAndPlace转换的CourseTimeTableDetail返回的是一个集合
            List<CourseTimeTableDetail> details =
                    timeAndPlace.convertToCourseTimeTableDetail(urpCourseTimeTable.getCourseRelativeInfo(), urpCourseTimeTable.getAttendClassTeacher());
            //把已经存在的课程详情过滤掉，将剩下的课程详情入库
            List<CourseTimeTableDetail> needInsertDetails = details.stream()
                    .filter(detail -> !courseTimeTableDetailDao.ifExistCourseTimeTableDetail(detail))
                    .collect(Collectors.toList());
            saveToDbAsync(needInsertDetails, timeAndPlace, student, basicInfo);
        }
    }

    private void saveToDbAsync(List<CourseTimeTableDetail> needInsertDetails, TimeAndPlace timeAndPlace, Student student, CourseTimeTableBasicInfo basicInfo){
        CompletableFuture.supplyAsync(() -> {
            needInsertDetails.stream().peek(detail -> detail.setCourseTimeTableBasicInfoId(basicInfo.getId()))
                    .forEach(detail -> {
                        courseTimeTableDetailDao.insertCourseTimeTableDetail(detail);
                        Room parseResult = roomService.parseToRoomForSpider(timeAndPlace.getClassroomName(), timeAndPlace.getTeachingBuildingName());
                        roomDao.saveOrGetRoomFromDb(parseResult);
                    });
            return needInsertDetails.stream().map(CourseTimeTableDetail::getId).collect(Collectors.toList());
        }).whenComplete((result, exception) -> result.forEach(detailId -> courseTimeTableDetailDao.insertClassesCourseTimeTable(student.getClasses().getId(), detailId)));
    }

    private CourseTimeTableBasicInfo getCourseTimeTableBasicInfo(UrpCourseTimeTable urpCourseTimeTable){
        Plan plan = getPlan(urpCourseTimeTable);
        CourseTimeTableBasicInfo basicInfoSpiderResult = urpCourseTimeTable.convertToCourseTimeTableBasicInfo();
        basicInfoSpiderResult.setPlanId(plan.getId());
        return courseTimeTableBasicInfoDao.saveOrGetCourseTimeTableBasicInfoFromDb(basicInfoSpiderResult);
    }

    private Plan getPlan(UrpCourseTimeTable urpCourseTimeTable) {
        return planDao.saveOrGetPlanFromDb(urpCourseTimeTable.convertToPlan());
    }

    /**
     * 计算节数
     * @param detail 课程时间表详情
     * @return 节数字符串
     */
    private String computationOfKnots(CourseTimeTableDetail detail){
       return detail.getOrder() + "-" + (detail.getOrder() + detail.getContinuingSession() - 1);
    }
}