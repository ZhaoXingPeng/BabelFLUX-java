package com.babelflux.backend.support;

import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;

/** Builds Mapper proxies that participate in Spring's transaction-bound JDBC connection. */
public final class MyBatisMapperTestSupport {
    private MyBatisMapperTestSupport() {}

    public static <T> T mapper(DataSource dataSource, Class<T> mapperType) {
        Configuration configuration = new Configuration(new Environment("test",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.addMapper(mapperType);
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        return new SqlSessionTemplate(factory).getMapper(mapperType);
    }
}
