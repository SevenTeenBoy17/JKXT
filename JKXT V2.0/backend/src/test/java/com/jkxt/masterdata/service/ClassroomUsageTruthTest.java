package com.jkxt.masterdata.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.jkxt.masterdata.api.MasterDataDtos.ClassroomDto;
import com.jkxt.masterdata.domain.Classroom;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class ClassroomUsageTruthTest {
  @ParameterizedTest
  @ValueSource(ints = {0, 30, 120})
  void capacityCannotBePresentedAsMeasuredOccupancy(int examCapacity) {
    Classroom room = new Classroom();
    room.setCode("SYNTHETIC-R1"); room.setName("Synthetic room"); room.setRoomType("standard");
    room.setCapacity(120); room.setExamCapacity(examCapacity);
    MasterDataService service = new MasterDataService(null, null, null, null, null, null,
        new com.jkxt.demo.DemoImportLimits(new com.jkxt.common.config.DeployProperties(),
            org.mockito.Mockito.mock(com.jkxt.demo.WorkspaceFence.class),org.mockito.Mockito.mock(com.jkxt.demo.DemoQuotaService.class)));
    ClassroomDto dto = ReflectionTestUtils.invokeMethod(service, "toClassroomDto", room);
    assertThat(dto.usage()).isNull();
    assertThat(dto.examCapacity()).isEqualTo(examCapacity);
    assertThat(dto.capacity()).isEqualTo(120);
    assertThat(dto.code()).isEqualTo("SYNTHETIC-R1");
  }
}
