package cpath.jpa;

import javax.persistence.*;

import cpath.service.CPathUtils;
import org.apache.commons.lang.builder.EqualsBuilder;
import org.apache.commons.lang.builder.HashCodeBuilder;
import org.hibernate.annotations.DynamicInsert;
import org.hibernate.annotations.DynamicUpdate;
import org.springframework.util.Assert;

/**
 * Id-mapping Entity.
 * @author rodche
 */
@Entity
@DynamicUpdate
@DynamicInsert
@Table(
	name="mappings",
	indexes = {
		@Index(name="src_index", columnList = "src"),
		@Index(name="srcId_index", columnList = "srcId"),
		@Index(name="dest_index", columnList = "dest"),
		@Index(name="destId_index", columnList = "destId"),
		@Index(name="dest_destId_index", columnList = "dest,destId"),
		@Index(name="srcId_dest_index", columnList = "srcId,dest"),
		@Index(name="src_srcId_dest_index", columnList = "src,srcId,dest"),
	}
)
public final class Mapping {
		
	@Id
	@GeneratedValue(strategy=GenerationType.AUTO)
	private Long id;
	
	@Column(nullable=false, length = 30) //e.g., "nucleotide sequence database",
	private String src;
	
	@Column(nullable=false, length = 15) //now, it can be either 'CHEBI' or 'UNIPROT' only.
	private String dest;
		
	@Column(nullable=false, length = 30) //InChIKey ~27 sym (longest ID type); won't map names > 30 symbols
	private String srcId; 
	
	@Column(nullable=false, length = 15)
	private String destId;

	public Mapping() {}

 
    public Mapping(String src, String srcId, String dest, String destId) {
		Assert.hasText(src);
		Assert.hasText(srcId);
		Assert.hasText(dest);
		Assert.hasText(destId);
		Assert.isTrue(srcId.length()<=30);
		Assert.isTrue(src.length()<=30);
		Assert.isTrue(destId.length()<=15);
		Assert.isTrue(dest.length()<=15);

		src = src.toUpperCase();
		//replace a uniprot* db name with simply 'UNIPROT'
		if(src.startsWith("UNIPROT") || src.startsWith("SWISSPROT"))
			src = "UNIPROT";
		else if(src.startsWith("PUBCHEM") && (src.contains("COMPOUND") || src.contains("CID"))) {
			src = "PUBCHEM-COMPOUND";
		}
		else if(src.startsWith("PUBCHEM") && (src.contains("SUBSTANCE") || src.contains("SID"))) {
			src = "PUBCHEM-SUBSTANCE";
		}

		srcId = CPathUtils.fixSourceIdForMapping(src, srcId);

		this.src = src;
		this.srcId = srcId;
		this.dest = dest.toUpperCase();
		this.destId = destId;
    }
    
    Long getId() {
    	return id;
    }

	public String getSrc() {
		return src;
	}

	public String getDest() {
		return dest;
	}

	public String getSrcId() {
		return srcId;
	}

	public String getDestId() {
		return destId;
	}

	@Override
    public String toString() {
    	return src + ":" + srcId + "," + dest + ":" + destId;
    }

	@Override
	public boolean equals(Object obj) {
		if(obj instanceof Mapping) {
			final Mapping that = (Mapping) obj;
			return new EqualsBuilder()
					.append(src, that.getSrc())
					.append(srcId, that.getSrcId())
					.append(dest, that.getDest())
					.append(destId, that.getDestId())
					.isEquals();
		}
		else
			return false;
	}

	@Override
	public int hashCode() {
		return new HashCodeBuilder()
				.append(src).append(srcId).append(dest).append(destId).toHashCode();
	}
}
