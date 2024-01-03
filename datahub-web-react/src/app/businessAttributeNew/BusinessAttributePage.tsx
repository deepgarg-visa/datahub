import React, { useEffect, useState } from 'react';
import { Button, Typography } from 'antd';
import { PlusOutlined } from '@ant-design/icons';
import styled from 'styled-components/macro';
import TabToolbar from '../entity/shared/components/styled/TabToolbar';
import BusinessAttributeEntitiesList from './BusinessAttributeEntitiesList';
import EmptyBusinessAttributeSection from './EmptyBusinessAttributeSection';
import CreateBusinessAttributeModal from '../businessAttribute/CreateBusinessAttributeModal';
import { Message } from '../shared/Message';
import { useGlossaryEntityData } from '../entity/shared/GlossaryEntityContext';
import { useUserContext } from '../context/useUserContext';
import { useListBusinessAttributesQuery } from '../../graphql/businessAttribute.generated';

export const HeaderWrapper = styled(TabToolbar)`
    padding: 15px 45px 10px 24px;
    height: auto;
`;

const BusinessAttributeWrapper = styled.div`
    display: flex;
    flex: 1;
    max-height: inherit;
`;

const MainContentWrapper = styled.div`
    display: flex;
    flex: 1;
    flex-direction: column;
`;

export const MAX_BROWSER_WIDTH = 500;
export const MIN_BROWSWER_WIDTH = 200;

function BusinessAttributePage() {
    const { refetch: refetchForTerms, loading: termsLoading } = useListBusinessAttributesQuery();
    const {
        loading: businessAttributeLoading,
        error: businessAttributeError,
        data: businessAttributeData,
        refetch: businessAttributeRefetch,
    } = useListBusinessAttributesQuery({
        variables: {
            query: '',
        },
    });
    console.log(businessAttributeLoading);
    console.log(businessAttributeRefetch);
    const { setEntityData } = useGlossaryEntityData();

    useEffect(() => {
        setEntityData(null);
    }, [setEntityData]);

    const businessAttributes = businessAttributeData?.listBusinessAttributes?.businessAttributes;

    const hasBusinessAttributes = !!businessAttributeData?.listBusinessAttributes?.total;

    const [isCreateBusinessAttributeModalVisible, setIsCreateBusinessAttributeModalVisible] = useState(false);

    const user = useUserContext();
    console.log('user ', user);
    // const canManageGlossaries = user?.platformPrivileges?.manageGlossaries;

    return (
        <>
            <BusinessAttributeWrapper>
                {termsLoading && <Message type="loading" content="Loading Glossary..." style={{ marginTop: '10%' }} />}
                {businessAttributeError && (
                    <Message type="error" content="Failed to load Business Attribute! An unexpected error occurred." />
                )}
                <MainContentWrapper data-testid="business-attributes-list">
                    <HeaderWrapper>
                        <Typography.Title level={3}>Business Attributes</Typography.Title>
                        <div>
                            <Button
                                data-testid="add-business-attribute-button"
                                // id={BUSINESS_GLOSSARY_CREATE_TERM_ID}
                                // disabled={!canManageGlossaries}
                                type="text"
                                onClick={() => setIsCreateBusinessAttributeModalVisible(true)}
                            >
                                <PlusOutlined /> Add Business Attribute
                            </Button>
                        </div>
                    </HeaderWrapper>
                    {hasBusinessAttributes && (
                        <BusinessAttributeEntitiesList businessAttributes={businessAttributes || []} />
                    )}
                    {!businessAttributeLoading && !hasBusinessAttributes && (
                        <EmptyBusinessAttributeSection
                            title="Empty Business Attribute"
                            description="Create Business Attributes for data consistency"
                            refetchForTerms={refetchForTerms}
                            refetchForNodes={businessAttributeRefetch}
                        />
                    )}
                </MainContentWrapper>
            </BusinessAttributeWrapper>
            {isCreateBusinessAttributeModalVisible && (
                <CreateBusinessAttributeModal
                    visible={isCreateBusinessAttributeModalVisible}
                    onClose={() => setIsCreateBusinessAttributeModalVisible(false)}
                />
            )}
        </>
    );
}

export default BusinessAttributePage;
